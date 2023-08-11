package net.devtech.arrp.impl

import com.google.common.util.concurrent.ThreadFactoryBuilder
import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import net.devtech.arrp.api.RuntimeResourcePack
import net.devtech.arrp.json.animation.JAnimation
import net.devtech.arrp.json.blockstate.JMultipart
import net.devtech.arrp.json.blockstate.JState
import net.devtech.arrp.json.blockstate.JVariant
import net.devtech.arrp.json.blockstate.JWhen
import net.devtech.arrp.json.lang.JLang
import net.devtech.arrp.json.loot.JCondition
import net.devtech.arrp.json.loot.JFunction
import net.devtech.arrp.json.loot.JLootTable
import net.devtech.arrp.json.loot.JPool
import net.devtech.arrp.json.models.JModel
import net.devtech.arrp.json.models.JTextures
import net.devtech.arrp.json.recipe.*
import net.devtech.arrp.json.tags.JTag
import net.devtech.arrp.util.CallableFunction
import net.devtech.arrp.util.CountingInputStream
import net.devtech.arrp.util.UnsafeByteArrayOutputStream
import net.minecraft.resource.ResourcePack
import net.minecraft.resource.ResourceType
import net.minecraft.resource.metadata.ResourceMetadataReader
import net.minecraft.util.Identifier
import org.apache.logging.log4j.LogManager
import org.jetbrains.annotations.ApiStatus
import java.awt.image.BufferedImage
import java.io.*
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.*
import java.util.concurrent.*
import java.util.concurrent.locks.Lock
import java.util.concurrent.locks.ReentrantLock
import java.util.function.*
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.imageio.ImageIO
import kotlin.math.max

@ApiStatus.Internal
internal class RuntimeResourcePackImpl
@JvmOverloads
constructor(
    private val id: Identifier,
    private val packVersion: Int = 5,
) : RuntimeResourcePack, ResourcePack {

    companion object {
        val EXECUTOR_SERVICE: ExecutorService
        val DUMP: Boolean
        val DEBUG_PERFORMANCE: Boolean

        private val GSON = GsonBuilder()
            .setPrettyPrinting()
            .disableHtmlEscaping()
            .registerTypeAdapter(JMultipart::class.java, JMultipart.Serializer())
            .registerTypeAdapter(JWhen::class.java, JWhen.Serializer())
            .registerTypeAdapter(JState::class.java, JState.Serializer())
            .registerTypeAdapter(JVariant::class.java, JVariant.Serializer())
            .registerTypeAdapter(JTextures::class.java, JTextures.Serializer())
            .registerTypeAdapter(JAnimation::class.java, JAnimation.Serializer())
            .registerTypeAdapter(JFunction::class.java, JFunction.Serializer())
            .registerTypeAdapter(JPool::class.java, JPool.Serializer())
            .registerTypeAdapter(JPattern::class.java, JPattern.Serializer())
            .registerTypeAdapter(JKeys::class.java, JKeys.Serializer())
            .registerTypeAdapter(JIngredient::class.java, JIngredient.Serializer())
            .registerTypeAdapter(JIngredients::class.java, JIngredients.Serializer())
            .registerTypeAdapter(Identifier::class.java, Identifier.Serializer())
            .registerTypeAdapter(JCondition::class.java, JCondition.Serializer())
            .create()

        private val LOGGER = LogManager.getLogger("RRP")

        init {
            val properties = Properties()
            var processors = max((Runtime.getRuntime().availableProcessors() / 2 - 1).toDouble(), 1.0)
                .toInt()
            var dump = false
            var performance = false
            properties.setProperty("threads", processors.toString())
            properties.setProperty("dump assets", "false")
            properties.setProperty("debug performance", "false")
            val file = File("config/rrp.properties")
            try {
                FileReader(file).use { reader ->
                    properties.load(reader)
                    processors = properties.getProperty("threads").toInt()
                    dump = properties.getProperty("dump assets").toBoolean()
                    performance = properties.getProperty("debug performance").toBoolean()
                }
            } catch (t: Throwable) {
                LOGGER.warn("Invalid config, creating new one!")
                file.getParentFile().mkdirs()
                try {
                    FileWriter(file).use { writer ->
                        properties.store(
                            writer,
                            "number of threads RRP should use for generating resources"
                        )
                    }
                } catch (ex: IOException) {
                    LOGGER.error("Unable to write to RRP config!")
                    ex.printStackTrace()
                }
            }
            EXECUTOR_SERVICE = Executors.newFixedThreadPool(
                processors,
                ThreadFactoryBuilder().setDaemon(true).setNameFormat("ARRP-Workers-%s").build()
            )
            DUMP = dump
            DEBUG_PERFORMANCE = performance
        }

        private fun serialize(`object`: Any): ByteArray {
            val ubaos = UnsafeByteArrayOutputStream()
            val writer = OutputStreamWriter(ubaos)
            GSON.toJson(`object`, writer)
            try {
                writer.close()
            } catch (e: IOException) {
                throw RuntimeException(e)
            }
            return ubaos.bytes
        }

        private fun fix(identifier: Identifier, prefix: String, append: String): Identifier {
            return Identifier(identifier.namespace, prefix + '/' + identifier.path + '.' + append)
        }
    }

    private val waiting: Lock = ReentrantLock()
    private val data: MutableMap<Identifier, Supplier<ByteArray>> = ConcurrentHashMap()
    private val assets: MutableMap<Identifier, Supplier<ByteArray>> = ConcurrentHashMap()
    private val root: MutableMap<String, Supplier<ByteArray>> = ConcurrentHashMap()
    private val langMergable: MutableMap<Identifier, JLang> = ConcurrentHashMap()
    override fun addRecoloredImage(identifier: Identifier, target: InputStream, operator: IntUnaryOperator) {
        addLazyResource(
            ResourceType.CLIENT_RESOURCES,
            fix(identifier, "textures", "png")
        ) { _: RuntimeResourcePack?, _: Identifier? ->
            try {

                // optimize buffer allocation, input and output image after recoloring should be roughly the same size
                val `is` = CountingInputStream(target)
                // repaint image
                val base = ImageIO.read(`is`)
                val recolored = BufferedImage(base.width, base.height, BufferedImage.TYPE_INT_ARGB)
                for (y in 0 until base.height) {
                    for (x in 0 until base.width) {
                        recolored.setRGB(x, y, operator.applyAsInt(base.getRGB(x, y)))
                    }
                }
                // write image
                val baos = UnsafeByteArrayOutputStream(`is`.bytes())
                ImageIO.write(recolored, "png", baos)
                return@addLazyResource baos.bytes
            } catch (e: Throwable) {
                e.printStackTrace()
                throw RuntimeException(e)
            }
        }
    }

    override fun addLang(identifier: Identifier, lang: JLang): ByteArray {
        return addAsset(fix(identifier, "lang", "json"), serialize(lang.lang))
    }

    override fun mergeLang(identifier: Identifier, lang: JLang) {
        langMergable.compute(identifier) { _: Identifier?, lang1: JLang? ->
            var lang1 = lang1
            if (lang1 == null) {
                lang1 = JLang()
                addLazyResource(
                    ResourceType.CLIENT_RESOURCES,
                    identifier
                ) { pack: RuntimeResourcePack, _: Identifier? -> pack.addLang(identifier, lang) }
            }
            lang1.lang.putAll(lang.lang)
            lang1
        }
    }

    override fun addLootTable(identifier: Identifier, table: JLootTable): ByteArray {
        return addData(fix(identifier, "loot_tables", "json"), serialize(table))
    }

    override fun addAsyncResource(
        type: ResourceType,
        path: Identifier,
        data: CallableFunction<Identifier, ByteArray>,
    ): Future<ByteArray> {
        val future = EXECUTOR_SERVICE.submit<ByteArray> { data[path] }
        getSys(type)[path] = Supplier<ByteArray> {
            try {
                return@Supplier future.get()
            } catch (e: InterruptedException) {
                throw RuntimeException(e)
            } catch (e: ExecutionException) {
                throw RuntimeException(e)
            }
        }
        return future
    }

    override fun addLazyResource(
        type: ResourceType,
        path: Identifier,
        func: BiFunction<RuntimeResourcePack, Identifier, ByteArray>,
    ) {
        getSys(type)[path] = Memoized(func, path)
    }

    override fun addResource(type: ResourceType, path: Identifier, data: ByteArray): ByteArray {
        getSys(type)[path] = Supplier { data }
        return data
    }

    override fun addAsyncRootResource(path: String, data: CallableFunction<String, ByteArray>): Future<ByteArray> {
        val future = EXECUTOR_SERVICE.submit<ByteArray> { data[path] }
        this.root[path] = Supplier<ByteArray> {
            try {
                return@Supplier future.get()
            } catch (e: InterruptedException) {
                throw RuntimeException(e)
            } catch (e: ExecutionException) {
                throw RuntimeException(e)
            }
        }
        return future
    }

    override fun addRootResource(path: String, data: ByteArray): ByteArray {
        this.root[path] = Supplier { data }
        return data
    }

    override fun addAsset(path: Identifier, data: ByteArray): ByteArray {
        return addResource(ResourceType.CLIENT_RESOURCES, path, data)
    }

    override fun addData(path: Identifier, data: ByteArray): ByteArray {
        return addResource(ResourceType.SERVER_DATA, path, data)
    }

    override fun addModel(model: JModel, path: Identifier): ByteArray {
        return addAsset(fix(path, "models", "json"), serialize(model))
    }

    override fun addBlockState(state: JState, path: Identifier): ByteArray {
        return addAsset(fix(path, "blockstates", "json"), serialize(state))
    }

    override fun addTexture(id: Identifier, image: BufferedImage): ByteArray {
        val ubaos = UnsafeByteArrayOutputStream()
        try {
            ImageIO.write(image, "png", ubaos)
        } catch (e: IOException) {
            throw RuntimeException("impossible.", e)
        }
        return addAsset(fix(id, "textures", "png"), ubaos.bytes)
    }

    override fun addAnimation(id: Identifier, animation: JAnimation): ByteArray {
        return addAsset(fix(id, "textures", "png.mcmeta"), serialize(animation))
    }

    override fun addTag(id: Identifier, tag: JTag): ByteArray {
        return addData(fix(id, "tags", "json"), serialize(tag))
    }

    override fun addRecipe(id: Identifier, recipe: JRecipe): ByteArray {
        return addData(fix(id, "recipes", "json"), serialize(recipe))
    }

    override fun async(action: Consumer<RuntimeResourcePack>): Future<*> {
        lock()
        return EXECUTOR_SERVICE.submit {
            action.accept(this)
            waiting.unlock()
        }
    }

    override fun dumpDirect(output: Path) {
        LOGGER.info("dumping $id's assets and data")
        // data dump time
        try {
            for ((key, value) in this.root) {
                val root = output.resolve(key)
                Files.createDirectories(root.parent)
                Files.write(root, value.get())
            }
            val assets = output.resolve("assets")
            Files.createDirectories(assets)
            for ((key, value) in this.assets) {
                write(assets, key, value.get())
            }
            val data = output.resolve("data")
            Files.createDirectories(data)
            for ((key, value) in this.data) {
                write(data, key, value.get())
            }
        } catch (exception: IOException) {
            throw RuntimeException(exception)
        }
    }

    @Throws(IOException::class)
    override fun load(dir: Path) {
        val stream = Files.walk(dir)
        for (file in Iterable {
            stream.filter { path: Path -> Files.isRegularFile(path) }
                .map { other: Path -> dir.relativize(other) }.iterator()
        }) {
            val s = file.toString()
            if (s.startsWith("assets")) {
                val path = s.substring("assets".length + 1)
                this.load(path, assets, Files.readAllBytes(file))
            } else if (s.startsWith("data")) {
                val path = s.substring("data".length + 1)
                this.load(path, data, Files.readAllBytes(file))
            } else {
                val data = Files.readAllBytes(file)
                this.root[s] = Supplier { data }
            }
        }
    }

    @Deprecated("Deprecated in Java", ReplaceWith("this.dump(Paths.get(output.toURI()))", "java.nio.file.Paths"))
    override fun dump(output: File) {
        this.dump(Paths.get(output.toURI()))
    }

    @Throws(IOException::class)
    override fun dump(zos: ZipOutputStream) {
        lock()
        for ((key, value) in this.root) {
            zos.putNextEntry(ZipEntry(key))
            zos.write(value.get())
            zos.closeEntry()
        }
        for ((_, value) in assets) {
            zos.putNextEntry(ZipEntry("assets/" + id.namespace + "/" + id.path))
            zos.write(value.get())
            zos.closeEntry()
        }
        for ((_, value) in data) {
            zos.putNextEntry(ZipEntry("data/" + id.namespace + "/" + id.path))
            zos.write(value.get())
            zos.closeEntry()
        }
        waiting.unlock()
    }

    @Throws(IOException::class)
    override fun load(stream: ZipInputStream) {
        sequence<ZipEntry?> { stream.nextEntry }
            .takeWhile { it == null }
            .filterNotNull()
            .forEach { entry ->
                val s = entry.toString()
                if (s.startsWith("assets")) {
                    val path = s.substring("assets".length + 1)
                    this.load(path, assets, read(entry, stream))
                } else if (s.startsWith("data")) {
                    val path = s.substring("data".length + 1)
                    this.load(path, data, read(entry, stream))
                } else {
                    val data = read(entry, stream)
                    this.root[s] = Supplier { data }
                }
            }
    }

    override fun getId(): Identifier {
        return id
    }

    /**
     * pack.png and that's about it I think/hope
     *
     * @param fileName the name of the file, can't be a path tho
     * @return the pack.png image as a stream
     */
    override fun openRoot(fileName: String): InputStream? {
        return if (!fileName.contains("/") && !fileName.contains("\\")) {
            lock()
            val supplier = this.root[fileName]
            if (supplier == null) {
                waiting.unlock()
                return null
            }
            waiting.unlock()
            ByteArrayInputStream(supplier.get())
        } else {
            throw IllegalArgumentException("File name can't be a path")
        }
    }

    override fun open(type: ResourceType, id: Identifier): InputStream? {
        lock()
        val supplier = getSys(type)[id]
        if (supplier == null) {
            LOGGER.warn("No resource found for $id")
            waiting.unlock()
            return null
        }
        waiting.unlock()
        return ByteArrayInputStream(supplier.get())
    }

    override fun findResources(
        type: ResourceType,
        namespace: String,
        prefix: String,
        maxDepth: Int,
        pathFilter: Predicate<String>,
    ): Collection<Identifier> {
        lock()
        val identifiers: MutableSet<Identifier> = HashSet()
        for (identifier in getSys(type).keys) {
            if (identifier.namespace == namespace && identifier.path.startsWith(prefix) && pathFilter.test(identifier.path)) {
                identifiers.add(identifier)
            }
        }
        waiting.unlock()
        return identifiers
    }

    override fun contains(type: ResourceType, id: Identifier): Boolean {
        lock()
        val contains = getSys(type).containsKey(id)
        waiting.unlock()
        return contains
    }

    override fun getNamespaces(type: ResourceType): Set<String> {
        lock()
        val namespaces: MutableSet<String> = HashSet()
        for (identifier in getSys(type).keys) {
            namespaces.add(identifier.namespace)
        }
        waiting.unlock()
        return namespaces
    }

    // if it works, don't touch it
    override fun <T> parseMetadata(metaReader: ResourceMetadataReader<T>): T? {
        if (metaReader.key == "pack") {
            val `object` = JsonObject()
            `object`.addProperty("pack_format", packVersion)
            `object`.addProperty("description", "runtime resource pack")
            return metaReader.fromJson(`object`)
        }
        LOGGER.info("'" + metaReader.key + "' is an unsupported metadata key!")
        return metaReader.fromJson(JsonObject())
    }

    override fun getName(): String {
        return "Runtime Resource Pack $id"
    }

    override fun close() {
        LOGGER.info("closing rrp $id")

        // lock
        lock()
        if (DUMP) {
            this.dump()
        }

        // unlock
        waiting.unlock()
    }

    @Throws(IOException::class)
    private fun read(entry: ZipEntry, stream: InputStream): ByteArray {
        val data = ByteArray(Math.toIntExact(entry.size))
        if (stream.read(data) != data.size) {
            throw IOException("Zip stream was cut off! (maybe incorrect zip entry length? maybe u didn't flush your stream?)")
        }
        return data
    }

    private fun load(fullPath: String, map: MutableMap<Identifier, Supplier<ByteArray>>, data: ByteArray) {
        val sep = fullPath.indexOf('/')
        val namespace = fullPath.substring(0, sep)
        val path = fullPath.substring(sep + 1)
        map[Identifier(namespace, path)] = Supplier { data }
    }

    private fun lock() {
        if (!waiting.tryLock()) {
            if (DEBUG_PERFORMANCE) {
                val start = System.currentTimeMillis()
                waiting.lock()
                val end = System.currentTimeMillis()
                LOGGER.warn("waited " + (end - start) + "ms for lock in RRP: " + id)
            } else {
                waiting.lock()
            }
        }
    }

    private fun write(dir: Path, identifier: Identifier, data: ByteArray) {
        try {
            val file = dir.resolve(identifier.namespace).resolve(identifier.path)
            Files.createDirectories(file.parent)
            Files.newOutputStream(file).use { output -> output.write(data) }
        } catch (e: IOException) {
            throw RuntimeException(e)
        }
    }

    private fun getSys(side: ResourceType): MutableMap<Identifier, Supplier<ByteArray>> {
        return if (side == ResourceType.CLIENT_RESOURCES) assets else data
    }

    private fun<T> Memoized(func: BiFunction<RuntimeResourcePack, T, ByteArray>, path: T): Memoized<T> = Memoized(func, path, this)

    class Memoized<T>(private val func: BiFunction<RuntimeResourcePack, T, ByteArray>, private val path: T, private val rrp: RuntimeResourcePack): Supplier<ByteArray> {
        private lateinit var data: ByteArray
        override fun get(): ByteArray {
            if (!::data.isInitialized) {
                data = func.apply(rrp, path)
            }
            return data
        }
    }
}
