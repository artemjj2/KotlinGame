import org.joml.Matrix4f
import org.joml.Vector3f
import org.joml.Vector4f
import org.lwjgl.assimp.*
import org.lwjgl.glfw.GLFW.*
import org.lwjgl.opengl.GL.*
import org.lwjgl.opengl.GL46.*
import org.lwjgl.stb.STBImage
import org.lwjgl.stb.STBTTAlignedQuad
import org.lwjgl.stb.STBTTBakedChar
import org.lwjgl.stb.STBTruetype
import org.lwjgl.system.MemoryStack
import kotlin.math.max
import kotlin.math.min



import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

import org.lwjgl.openal.AL
import org.lwjgl.openal.AL10.*
import org.lwjgl.openal.ALC
import org.lwjgl.openal.ALC10.*
import org.lwjgl.system.MemoryUtil.NULL
import org.lwjgl.stb.STBVorbis
import org.lwjgl.BufferUtils
import org.lwjgl.stb.STBVorbisInfo

import java.awt.*
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.IntBuffer
import javax.swing.event.MenuEvent
import kotlin.math.cos
import kotlin.math.sin

import java.util.concurrent.CopyOnWriteArrayList
var autoAmo = 5
var drobAmo = 5
var curAmmo = 999
var isVsync = false
var isSky = true
var lastX = 400.0
var lastY = 300.0
var firstMouse = true
var sensitivity = 0.1f
var isleftPress = false
var curID = -1

var isallDeath = false
var isallReady = false

var inMainMenu = true
var nickname = ""
var ipAddress = ""

var hp = 0

var isTabPressed = true // Флаг для отслеживания нажатия клавиши Tab

enum class MenuState {
    ENTER_NICKNAME,
    ENTER_IP,
    ERORR,
    FINISHED
}

@Serializable
data class Player(
    var deaths: Int = 0,
    var kills: Int = 0,
    var id: Int = -1,
    var nickname: String,
    var hp: Int = 100,
    var positionX: Float = 0f,
    var positionY: Float = 0f,
    var positionZ: Float = 0f,
    var yaw: Float = 0f,
    var pitch: Float = 0f,
    var isReady: Boolean = false,
    var isDead: Boolean = false,
    var currentWeapon: Int = 0,
    var ammo: Int = 1,
    var isAllDead: Boolean = false,
    var isAllReady: Boolean = false
)

@Serializable
data class DamageRequest(
    val attackerId: Int = -1,
    val targetId: Int = -1,
    val damage: Int = 0
)

fun rayIntersectsEnemy(rayOrigin: Vector3f, rayDirection: Vector3f, enemyPosition: Vector3f): Float? {
    val enemySize = Vector3f(0.5f, 1.8f, 0.5f) // Размер области врага (полукуб)
    val minBound = Vector3f(enemyPosition).sub(enemySize)
    val maxBound = Vector3f(enemyPosition).add(enemySize)

    val tMin = Vector3f(minBound).sub(rayOrigin).div(rayDirection)
    val tMax = Vector3f(maxBound).sub(rayOrigin).div(rayDirection)

    val t1 = Vector3f(
        min(tMin.x, tMax.x),
        min(tMin.y, tMax.y),
        min(tMin.z, tMax.z)
    )

    val t2 = Vector3f(
        max(tMin.x, tMax.x),
        max(tMin.y, tMax.y),
        max(tMin.z, tMax.z)
    )

    val tNear = max(max(t1.x, t1.y), t1.z)
    val tFar = min(min(t2.x, t2.y), t2.z)

    return if (tNear > 0 && tNear < tFar) tNear else null
}

fun findNearestPlayer(rayOrigin: Vector3f, rayDirection: Vector3f, enemies: List<Player>): Player? {
    var nearestEnemy: Player? = null
    var minDistance = Float.POSITIVE_INFINITY

    for (enemy in enemies) {
        if((enemy.id != curID) or !enemy.isDead) {
            val distance =
                rayIntersectsEnemy(rayOrigin, rayDirection, Vector3f(enemy.positionX, enemy.positionY, enemy.positionZ))
            if (distance != null && distance < minDistance) {
                minDistance = distance
                nearestEnemy = enemy
            }
        }
    }

    return nearestEnemy
}

var player = Player(0,0,-1, "a", 100, 0f, 0f, 0f, 0f, 0f, false, false, 0, curAmmo, false, false)
val players = CopyOnWriteArrayList<Player>()

suspend fun connectToServer(client: HttpClient, serverIp: String): Int {
    return try {
        client.post("http://$serverIp:8080/connect") {
            contentType(ContentType.Application.Json)
            setBody(player)
        }.body()

    } catch (e: Exception) {
        println("Ошибка подключения: ${e.message}")
        -1
    }
}

suspend fun disconnect(client: HttpClient, serverIp: String, playerId: Int) {
    try {
        val response = client.post("http://$serverIp:8080/disconnect") {
            contentType(ContentType.Application.Json)
            setBody(playerId)
        }.body<String>()
        println(response)
        curID = -1
    } catch (e: Exception) {
        println("Ошибка при отключении: ${e.message}")
    }
}

suspend fun dealDamage(client: HttpClient, serverIp: String, attackerId: Int, targetId: Int, damage: Int) {
    try {
        val damageRequest = DamageRequest(attackerId, targetId, damage)
        val response = client.post("http://$serverIp:8080/damage") {
            contentType(ContentType.Application.Json)
            setBody(damageRequest)
        }.body<String>()
        println(response)
    } catch (e: Exception) {
        println("Ошибка при нанесении урона: ${e.message}")
    }
}

suspend fun updatePlayerData(client: HttpClient, serverIp: String) {
    try {
        val updatedPlayers = client.post("http://$serverIp:8080/update") {
            contentType(ContentType.Application.Json)
            setBody(player)
        }.body<List<Player>>()
        players.clear()
        players.addAll(updatedPlayers) // Обновление локального списка игроков
    } catch (e: Exception) {
        println("Ошибка обновления данных: ${e.message}")
    }
}

public val client = HttpClient(CIO) {
    install(ContentNegotiation) {
        json(Json { ignoreUnknownKeys = true })
    }
}

fun initAudio() {
    val device = alcOpenDevice(null as CharSequence?)
    if (device == NULL) {
        throw IllegalStateException("Не удалось открыть устройство OpenAL")
    }

    val context = alcCreateContext(device, null as IntArray?)
    if (context == NULL) {
        throw IllegalStateException("Не удалось создать контекст OpenAL")
    }

    alcMakeContextCurrent(context)
    AL.createCapabilities(ALC.createCapabilities(device))
}

fun closeAudio() {
    val currentContext = alcGetCurrentContext()
    val device = alcGetContextsDevice(currentContext)
    alcMakeContextCurrent(NULL)
    alcDestroyContext(currentContext)
    alcCloseDevice(device)
}

fun loadSound(filePath: String): Int {
    val buffer = alGenBuffers()

    MemoryStack.stackPush().use { stack ->
        // Переменные для декодирования
        val error = stack.mallocInt(1)
        val vorbis = STBVorbis.stb_vorbis_open_filename(filePath, error, null)
            ?: throw RuntimeException("Не удалось загрузить аудио файл $filePath. Код ошибки: ${error[0]}")

        // Получение информации о звуке
        val info = STBVorbisInfo.mallocStack(stack)
        STBVorbis.stb_vorbis_get_info(vorbis, info)

        // Декодирование в PCM-данные
        val pcmData = BufferUtils.createByteBuffer(
            STBVorbis.stb_vorbis_stream_length_in_samples(vorbis) * info.channels() * 2 // 16-bit аудио
        )
        val samples = STBVorbis.stb_vorbis_get_samples_short_interleaved(
            vorbis, info.channels(), pcmData.asShortBuffer()
        )

        if (samples == 0) {
            throw RuntimeException("Ошибка декодирования аудио файла $filePath")
        }

        // Определяем формат OpenAL
        val format = if (info.channels() == 1) AL_FORMAT_MONO16 else AL_FORMAT_STEREO16

        // Передача данных в OpenAL
        alBufferData(buffer, format, pcmData, info.sample_rate())

        // Освобождаем ресурсы STB
        STBVorbis.stb_vorbis_close(vorbis)
    }

    return buffer
}

fun playSound(buffer: Int, loop: Boolean = false) {
    val source = alGenSources()
    alSourcei(source, AL_BUFFER, buffer)
    alSourcei(source, AL_LOOPING, if (loop) AL_TRUE else AL_FALSE)
    alSourcePlay(source)
}

fun renderPlayerStats(players: List<Player>, textRenderer: TextRenderer, screenWidth: Int, screenHeight: Int) {
    if (!isTabPressed) return // Выводим статистику только при нажатии Tab

    val startX = screenWidth / 2 - 200f // Начальная позиция X для текста
    var startY = screenHeight/ 2 - 100f // Начальная позиция Y для текста
    val lineSpacing = 70f // Расстояние между строками
    val padding = 30f // Отступы внутри квадрата
    val boxWidth = 800f // Ширина фона
    val boxHeight = (players.size + 1) * lineSpacing + padding * 2 // Высота фона

    // Рисуем белый квадрат на фоне
    drawBackgroundBox(startX - padding*2, startY - padding*2, boxWidth, boxHeight, screenWidth, screenHeight)

    // Заголовок таблицы
    textRenderer.renderText(
        text = "Player Statistics",
        x = startX,
        y = startY,
        screenWidth = screenWidth,
        screenHeight = screenHeight,
        RBGCol = Vector4f(1f, 1f, 0f, 1f) // Желтый цвет
    )
    startY += lineSpacing

    // Вывод статистики для каждого игрока
    players.forEach { player ->
        val statsText = "${player.nickname} | Kills: ${player.kills} | Deaths: ${player.deaths}"
        textRenderer.renderText(
            text = statsText,
            x = startX,
            y = startY,
            screenWidth = screenWidth,
            screenHeight = screenHeight,
            RBGCol = Vector4f(0f, 0f, 0f, 1f) // Черный текст
        )
        startY += lineSpacing
    }
}

// Функция для отрисовки фона (белого квадрата)
fun drawBackgroundBox(x: Float, y: Float, width: Float, height: Float, screenWidth: Int, screenHeight: Int) {
    // Устанавливаем 2D-матрицу проекции
    val projectionMatrix = Matrix4f().ortho2D(0f, screenWidth.toFloat(), screenHeight.toFloat(), 0f)

    glMatrixMode(GL_PROJECTION)
    glPushMatrix()
    glLoadMatrixf(projectionMatrix.get(FloatArray(16)))
    glDisable(GL_LIGHTING)

    glMatrixMode(GL_MODELVIEW)
    glPushMatrix()
    glLoadIdentity()

    glDisable(GL_TEXTURE_2D) // Отключаем текстуры
    glEnable(GL_BLEND) // Включаем смешивание
    glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA)

    glColor4f(0.7f, 0.7f, 0.7f, 1f) // Белый цвет с прозрачностью
    glBegin(GL_QUADS)
    glVertex2f(x, y)
    glVertex2f(x + width, y)
    glVertex2f(x + width, y + height)
    glVertex2f(x, y + height)
    glEnd()

    glDisable(GL_BLEND) // Отключаем смешивание
    glEnable(GL_TEXTURE_2D) // Включаем текстуры обратно

    // Восстанавливаем исходные матрицы
    glMatrixMode(GL_PROJECTION)
    glPopMatrix()

    glMatrixMode(GL_MODELVIEW)
    glPopMatrix()
    lightning()
}


var menuState = MenuState.ENTER_NICKNAME
var userInput = ""

fun renderMainMenu(window: Long, textRenderer: TextRenderer, screenWidth: Int, screenHeight: Int) {
    glClearColor(0.2f, 0.2f, 0.2f, 1.0f) // Серый фон
    glClear(GL_COLOR_BUFFER_BIT or GL_DEPTH_BUFFER_BIT)


    // Отрисовка текста "Главное меню"
    textRenderer.renderText(
        "Main menu",
        screenWidth / 2 - 200f,
        screenHeight / 2 - 350f,
        screenWidth,
        screenHeight,
        Vector4f(1f, 1f, 1f, 1f)
    )

    when (menuState) {
        MenuState.ENTER_NICKNAME -> {
            textRenderer.renderText(
                "Enter Nickname:",
                screenWidth / 2 - 250f,
                screenHeight / 2 - 150f,
                screenWidth,
                screenHeight,
                Vector4f(1f, 1f, 1f, 1f)
            )
        }
        MenuState.ENTER_IP -> {
            textRenderer.renderText(
                "Enter IP Address:",
                screenWidth / 2 - 250f,
                screenHeight / 2 - 150f,
                screenWidth,
                screenHeight,
                Vector4f(1f, 1f, 1f, 1f)
            )
        }
        MenuState.ERORR -> {
            textRenderer.renderText(
                "Can`t connect to server",
                screenWidth / 2 - 350f,
                screenHeight / 2 - 150f,
                screenWidth,
                screenHeight,
                Vector4f(1f, 1f, 1f, 1f)
            )
        }
        MenuState.FINISHED -> {
            textRenderer.renderText(
                "Press Enter to ready",
                screenWidth / 2 - 400f,
                screenHeight / 2 + 250f,
                screenWidth,
                screenHeight,
                Vector4f(1f, 1f, 1f, 1f)
            )
            players.forEachIndexed { index, value ->
                textRenderer.renderText(
                    "nickname: ${value.nickname}, ready: ${value.isReady}",
                    5f,
                    200f + 60f * (index+1),
                    screenWidth,
                    screenHeight,
                    Vector4f(1f, 1f, 1f, 1f)
                )}

        }
    }

    // Отрисовка текущего текстового поля
    textRenderer.renderText(
        userInput,
        screenWidth / 2 - 300f,
        screenHeight / 2 + 100f,
        screenWidth,
        screenHeight,
        Vector4f(1f, 1f, 1f, 1f)
    )
}

fun processMenuInput(window: Long, keys: MutableSet<Int>) {
    glfwSetCharCallback(window) { _, codepoint ->
        userInput += codepoint.toChar()
    }

    if (keys.contains(GLFW_KEY_BACKSPACE) && userInput.isNotEmpty()) {
        userInput = userInput.dropLast(1)
        keys.remove(GLFW_KEY_BACKSPACE)
    }

    if (keys.contains(GLFW_KEY_ENTER)) {
        when (menuState) {
            MenuState.ENTER_NICKNAME -> {
                nickname = userInput
                //userInput = "localhost"
                userInput = "192.168.1.14"
                menuState = MenuState.ENTER_IP
            }
            MenuState.ENTER_IP -> {
                ipAddress = userInput
                userInput = ""
                CoroutineScope(Dispatchers.IO).launch {
                    player.nickname = "$nickname"
                    curID = connectToServer(client, ipAddress)
                    player.id = curID
                    if (curID == -1) {
                        menuState = MenuState.ERORR

                    } else {
                        menuState = MenuState.FINISHED
                        CoroutineScope(Dispatchers.IO).launch {
                            while (curID != -1) {
                                updatePlayerData(client, ipAddress)
                            }
                            players.clear()
                        }
                    }
                    println("Игроки на сервере: $players")
                }
            }
            MenuState.ERORR -> {
                userInput = "localhost"
                menuState = MenuState.ENTER_IP
            }
            MenuState.FINISHED -> {
                player.isReady = !player.isReady
            }
        }
        keys.remove(GLFW_KEY_ENTER)
    }
}

fun lightning(){
    // Включение освещения
    glEnable(GL_LIGHTING)
    glEnable(GL_LIGHT0)

    // Включение нормализации нормалей для корректного освещения при масштабировании
    glEnable(GL_NORMALIZE)

    val lightPos = floatArrayOf(0.0f, 0.0f, 4.0f, 1.0f)
    glLightfv(GL_LIGHT0, GL_POSITION, lightPos)

    val diffuseLight = floatArrayOf(0.8f, 0.8f, 0.8f, 1.0f)
    glLightfv(GL_LIGHT0, GL_DIFFUSE, diffuseLight)

    val ambientLight = floatArrayOf(0.3f, 0.3f, 0.3f, 1.0f)
    glLightfv(GL_LIGHT0, GL_AMBIENT, ambientLight)

    val specularLight = floatArrayOf(0.3f, 0.3f, 0.3f, 1.0f)
    glLightfv(GL_LIGHT0, GL_SPECULAR, specularLight)

    glEnable(GL_COLOR_MATERIAL)
    glColorMaterial(GL_FRONT, GL_AMBIENT_AND_DIFFUSE)
}

class TextRenderer(fontPath: String, private val fontSize: Float) {
    private val bakedChars = STBTTBakedChar.malloc(96)
    private val fontTextureId: Int

    init {
        val fontData = loadFont(fontPath)
        val textureSize = 512
        val bitmap = ByteBuffer.allocateDirect(textureSize * textureSize)

        STBTruetype.stbtt_BakeFontBitmap(fontData, fontSize, bitmap, textureSize, textureSize, 32, bakedChars)
        fontTextureId = glGenTextures()
        glBindTexture(GL_TEXTURE_2D, fontTextureId)
        glTexImage2D(GL_TEXTURE_2D, 0, GL_ALPHA, textureSize, textureSize, 0, GL_ALPHA, GL_UNSIGNED_BYTE, bitmap)

        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR)
    }

    private fun loadFont(path: String): ByteBuffer {
        return FileInputStream(path).use { stream ->
            val bytes = stream.readBytes()
            ByteBuffer.allocateDirect(bytes.size).put(bytes).flip()
        }
    }

    fun renderText(
        text: String,
        x: Float,
        y: Float,
        screenWidth: Int,
        screenHeight: Int,
        RBGCol: Vector4f = Vector4f(1f,1f, 1f, 1f),
    ) {
        val projectionMatrix = Matrix4f().ortho2D(0f, screenWidth.toFloat(), screenHeight.toFloat(), 0f)

        glMatrixMode(GL_PROJECTION)
        glPushMatrix()
        glLoadMatrixf(projectionMatrix.get(FloatArray(16)))

        glMatrixMode(GL_MODELVIEW)
        glPushMatrix()
        glLoadIdentity()

        // Отключаем освещение и материалы, чтобы текст не был затронут освещением
        glDisable(GL_LIGHTING)
        glDisable(GL_DEPTH_TEST)

        glEnable(GL_TEXTURE_2D)
        glBindTexture(GL_TEXTURE_2D, fontTextureId)

        // Включаем смешивание для обработки прозрачности
        glEnable(GL_BLEND)
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA)

        // Устанавливаем цвет текста
        glColor4f(RBGCol.x, RBGCol.y, RBGCol.z, RBGCol.w)
        glBegin(GL_QUADS)

        val xpos = floatArrayOf(x)
        val ypos = floatArrayOf(y)

        text.forEach { char ->
            if (char !in 32.toChar()..127.toChar()) return@forEach
            val quad = STBTTAlignedQuad.malloc()
            STBTruetype.stbtt_GetBakedQuad(bakedChars, 512, 512, char.toInt() - 32, xpos, ypos, quad, true)
            glTexCoord2f(quad.s0(), quad.t0())
            glVertex2f(quad.x0(), quad.y0())
            glTexCoord2f(quad.s1(), quad.t0())
            glVertex2f(quad.x1(), quad.y0())
            glTexCoord2f(quad.s1(), quad.t1())
            glVertex2f(quad.x1(), quad.y1())
            glTexCoord2f(quad.s0(), quad.t1())
            glVertex2f(quad.x0(), quad.y1())
        }

        glEnd()
        glDisable(GL_TEXTURE_2D)
        glDisable(GL_BLEND)

        lightning()
        glEnable(GL_DEPTH_TEST)

        glMatrixMode(GL_PROJECTION)
        glPopMatrix()

        glMatrixMode(GL_MODELVIEW)
        glPopMatrix()
    }
}


class ModelRenderer(private val modelPath: String) {

    private lateinit var modelData: AIScene

    init {
        loadModel()
    }

    private fun loadModel() {
        val flags = Assimp.aiProcess_Triangulate or Assimp.aiProcess_FlipUVs or Assimp.aiProcess_GenSmoothNormals
        modelData = Assimp.aiImportFile(modelPath, flags)
            ?: throw RuntimeException("Не удалось загрузить модель: $modelPath")
    }

    fun render() {
        glPushMatrix()
        modelData.mRootNode()?.let { renderNode(it) }
        glPopMatrix()
    }

    private fun renderNode(node: AINode) {
        for (i in 0 until node.mNumMeshes()) {
            val mesh = AIMesh.create(modelData.mMeshes()!!.get(node.mMeshes()!!.get(i).toInt()))
            renderMesh(mesh)
        }
        for (i in 0 until node.mNumChildren()) {
            renderNode(AINode.create(node.mChildren()!!.get(i)))
        }
    }

    private fun renderMesh(mesh: AIMesh) {
        // Извлекаем материал
        val materialIndex = mesh.mMaterialIndex()
        val material = AIMaterial.create(modelData.mMaterials()!!.get(materialIndex))

        // Получаем цвет диффузного материала
        val diffuseColor = AIColor4D.create()
        Assimp.aiGetMaterialColor(material, Assimp.AI_MATKEY_COLOR_DIFFUSE, Assimp.aiTextureType_NONE, 0, diffuseColor)
        glColor4f(diffuseColor.r(), diffuseColor.g(), diffuseColor.b(), diffuseColor.a())

        // Устанавливаем параметры зеркального цвета материала
        val specularColor = AIColor4D.create()
        Assimp.aiGetMaterialColor(
            material,
            Assimp.AI_MATKEY_COLOR_SPECULAR,
            Assimp.aiTextureType_NONE,
            0,
            specularColor
        )
        glMaterialfv(GL_FRONT, GL_SPECULAR, floatArrayOf(0.1f, 0.1f, 0.1f, 1.0f)) // Уменьшение зеркального цвета
        glMaterialf(GL_FRONT, GL_SHININESS, 8.0f) // Уменьшение блеска

        glBegin(GL_TRIANGLES)
        for (i in 0 until mesh.mNumFaces()) {
            val face = mesh.mFaces().get(i)
            for (j in 0 until face.mNumIndices()) {
                val index = face.mIndices().get(j)
                val vertex = mesh.mVertices().get(index)

                // Устанавливаем текстурные координаты, если они существуют
                if (mesh.mTextureCoords(0) != null) {
                    val texCoord = mesh.mTextureCoords(0)!!.get(index)
                    glTexCoord2f(texCoord.x(), texCoord.y())
                }

                // Устанавливаем нормали для освещения
                if (mesh.mNormals() != null) {
                    val normal = mesh.mNormals()!!.get(index)
                    glNormal3f(normal.x(), normal.y(), normal.z())
                }

                glVertex3f(vertex.x(), vertex.y(), vertex.z())
            }
        }
        glEnd()
    }

}

class Skybox(private val texturePath: String, private val modelPath: String) {
    private var textureID: Int = 0
    private lateinit var modelRenderer: ModelRenderer

    init {
        loadTexture()
        modelRenderer = ModelRenderer(modelPath)
    }

    private fun loadTexture() {
        val image = loadImage(texturePath)
        textureID = glGenTextures()
        glBindTexture(GL_TEXTURE_2D, textureID)

        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR)

        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, image.width, image.height, 0, GL_RGBA, GL_UNSIGNED_BYTE, image.data)
        glGenerateMipmap(GL_TEXTURE_2D)
    }

    private fun loadImage(path: String): LoadedImage {
        val width = IntArray(1)
        val height = IntArray(1)
        val channels = IntArray(1)

        val imageBuffer: ByteBuffer = STBImage.stbi_load(path, width, height, channels, 4)
            ?: throw RuntimeException("Failed to load texture file: $path")

        return LoadedImage(imageBuffer, width[0], height[0])
    }

    fun render(camera: Camera) {
        glPushMatrix()
        glBindTexture(GL_TEXTURE_2D, textureID)
        glDepthMask(false)

        // Включение текстурирования
        glEnable(GL_TEXTURE_2D)
        glDisable(GL_LIGHTING) // Отключаем освещение для неба

        glTranslatef(camera.position.x, camera.position.y, camera.position.z)

        glRotatef(90f, -1f, 0f, 0f)

        glScalef(500.0f, 500.0f, 500.0f)
        // Отрисовка модели
        modelRenderer.render()
        glDisable(GL_TEXTURE_2D)
        glEnable(GL_LIGHTING) // Включаем освещение снова
        glDepthMask(true) // Включаем запись в буфер глубины
        glPopMatrix()
    }

    private data class LoadedImage(val data: ByteBuffer, val width: Int, val height: Int)
}

class Map(private val modelPath: String) {
    private lateinit var modelRenderer: ModelRenderer

    init {
        modelRenderer = ModelRenderer(modelPath)
    }

    fun render() {
        glPushMatrix()

        glRotatef(90f, -1f, 0f, 0f)
        glTranslatef(0.0f, 0.0f, 2.0f)
        glScalef(5.0f, 5.0f, 5.0f)
        // Отрисовка модели
        modelRenderer.render()
        glPopMatrix()
    }
}


class WeaponRenderer(private val weaponPaths: List<String>) {
    public var currentWeaponIndex = 0
    private lateinit var weaponModel: ModelRenderer
    private var isRotating = false
    private var rotationAngle = 0f // Угол вращения
    private var targetAngle = 0f // Целевой угол
    private var targetChange = 0f // Целевой
    private var weaponYOffset = 0f // Цел
    private var rotationSpeed = 1000f // Скорость вращения
    public var isChangingWeapon = false

    init {
        loadWeapon(currentWeaponIndex)
    }

    private fun loadWeapon(index: Int) {
        weaponModel = ModelRenderer(weaponPaths[index])
    }


    fun switchWeapon(index: Int) {
        if(!isRotating && !isChangingWeapon && currentWeaponIndex != index && ((index == 1 && drobAmo > 0) || (index == 2 && autoAmo > 0) || (index == 0))) {
            if (index in weaponPaths.indices) {
                isChangingWeapon = true
                currentWeaponIndex = index
                targetChange = -0.3f
                loadWeapon(currentWeaponIndex)
                when(index) {
                    0 -> {rotationSpeed = 750f
                        curAmmo = 999}
                    1 -> {rotationSpeed = 500f
                        curAmmo = drobAmo}
                    2 -> {rotationSpeed = 1000f
                        curAmmo = autoAmo}
                }
            }
        }
    }

    private fun computeRightVector(cameraFront: Vector3f, cameraUp: Vector3f): Vector3f {
        val rightVector = Vector3f(cameraFront).cross(cameraUp).normalize()
        return rightVector
    }

    private fun computeDownVector(cameraFront: Vector3f, rightVector: Vector3f): Vector3f {
        val downVector = Vector3f(rightVector).cross(Vector3f(cameraFront).negate()).normalize()
        return downVector
    }

    fun render(camera: Camera, dt: Float) {
        var RotationSpeed = rotationSpeed * dt
        glPushMatrix()

        // Движение оружия вниз/вверх при смене
        if (isChangingWeapon) {
            weaponYOffset += 3 * dt * (if (weaponYOffset > targetChange) -1 else 1)
            if ((weaponYOffset <= targetChange && targetChange < 0) || (weaponYOffset >= targetChange && targetChange >= 0)) {
                if (targetChange == -0.3f) {
                    loadWeapon(currentWeaponIndex)
                    targetChange = 0f // Поднимаем оружие обратно
                } else {
                    weaponYOffset = 0f
                    isChangingWeapon = false
                }
            }
        }

        // Вычисление векторов "вправо" и "вниз" от камеры
        val rightVector = computeRightVector(camera.front, camera.up)
        val downVector = computeDownVector(camera.front, rightVector)

        // Определение смещения вправо и вниз
        val rightOffset = 0.1f
        val downOffset = 0.1f

        // Смещение позиции оружия относительно камеры
        glTranslatef(
            camera.position.x + 0.25f * camera.front.x + rightOffset * rightVector.x + downOffset * downVector.x,
            camera.position.y + 0.25f * camera.front.y + 0.1f * rightVector.y + 0.1f * downVector.y + weaponYOffset,
            camera.position.z + 0.25f * camera.front.z + rightOffset * rightVector.z + downOffset * downVector.z
        )

        // Поворот оружия относительно камеры
        glRotatef(camera.yaw, 0f, 1f, 0f) // Поворот вокруг оси Y

        if (currentWeaponIndex == 0) {
            glRotatef(90f, 0f, 0f, 1f)
            glRotatef(90f, 0f, 1f, 0f)
        } else if (currentWeaponIndex == 1) {
            glRotatef(90f, 0f, 0f, 1f)
            glRotatef(90f, 0f, 1f, 0f)
        } else if (currentWeaponIndex == 2) {
            glRotatef(90f, 0f, 0f, 1f)
            glRotatef(90f, 0f, 1f, 0f)
        }
        glRotatef(camera.pitch, 0f, -1f, 0f) // Поворот вокруг оси X

        if (currentWeaponIndex == 0) {
            // Масштабирование модели оружия
            glScalef(0.01f, 0.01f, 0.01f)
        } else if (currentWeaponIndex == 1) {
            glScalef(0.03f, 0.03f, 0.03f)
        } else if (currentWeaponIndex == 2) {
            glScalef(0.01f, 0.01f, 0.01f)
        }

        if (isRotating) {
            if (currentWeaponIndex == 2) {

                // Поворот оружия на 10 градусов
                if (rotationAngle < targetAngle) {
                    rotationAngle += RotationSpeed
                    if (rotationAngle >= targetAngle) {
                        rotationAngle = targetAngle
                        // После завершения поворота устанавливаем целевой угол обратно
                        targetAngle = 0f
                    }
                }
                if (targetAngle == 0f) {
                    if (rotationAngle > targetAngle) {
                        rotationAngle -= RotationSpeed
                        if (rotationAngle <= targetAngle) {
                            rotationAngle = targetAngle
                            // После завершения поворота устанавливаем целевой угол обратно
                            targetAngle = 0f
                            isRotating = false
                            if(autoAmo == 0)
                                switchWeapon(0)
                        }
                    }
                }

            } else {
                if (rotationAngle < targetAngle) {
                    rotationAngle += RotationSpeed
                    if (rotationAngle >= targetAngle) {
                        rotationAngle = targetAngle
                        // После завершения поворота устанавливаем целевой угол обратно
                        isRotating = false
                        if(currentWeaponIndex == 1 && drobAmo == 0)
                            switchWeapon(0)
                    }
                }

            }
            glRotatef(rotationAngle, 0f, -1f, 0f) // Поворот вокруг оси Y
        }

        weaponModel.render()

        glPopMatrix()
    }

    // Метод для запуска вращения
    fun startRotation(camera: Camera) {
        if (!isRotating) {
            val Target = findNearestPlayer(camera.position, camera.front, players)
            if(currentWeaponIndex == 1) {
                drobAmo--
                curAmmo = drobAmo
                if(Target != null)
                CoroutineScope(Dispatchers.IO).launch {
                    dealDamage(client, ipAddress, curID, Target.id, (40..60).random())
                }
            }
            else if(currentWeaponIndex == 2) {
                autoAmo--
                curAmmo = autoAmo
                if(Target != null)
                CoroutineScope(Dispatchers.IO).launch {
                    dealDamage(client, ipAddress, curID, Target.id, (25..40).random())
                }
            }
            else{
                if(Target != null)
                CoroutineScope(Dispatchers.IO).launch {
                    dealDamage(client, ipAddress, curID, Target.id, (15..25).random())
                }
            }
            playSound(loadSound("Sound/Shoot.ogg"))
            isRotating = true
            targetAngle = 360f // Устанавливаем целевой угол на 10 градусов
            if (currentWeaponIndex == 2) {
                targetAngle = 25f // Устанавливаем целевой угол на 10 градусов
            }
            rotationAngle = 0f
        }
    }
}

class Camera(var position: Vector3f, var yaw: Float, var pitch: Float, var up: Vector3f, var front: Vector3f) {
    fun updateMovement(deltaTime: Float, keys: Set<Int>) {
        front.x = -sin(Math.toRadians(yaw.toDouble())).toFloat() * cos(Math.toRadians(pitch.toDouble())).toFloat()
        front.y = sin(Math.toRadians(pitch.toDouble())).toFloat()
        front.z = -cos(Math.toRadians(yaw.toDouble())).toFloat() * cos(Math.toRadians(pitch.toDouble())).toFloat()

        isTabPressed = false
        var speed = 3.0f * deltaTime
        if (keys.contains(GLFW_KEY_LEFT_SHIFT) || keys.contains(GLFW_KEY_RIGHT_SHIFT)) {
            speed *= 4f
        }
        if (keys.contains(GLFW_KEY_W)) {
            position.z -= speed * cos(Math.toRadians(yaw.toDouble())).toFloat()
            position.x -= speed * sin(Math.toRadians(yaw.toDouble())).toFloat()
        }
        if (keys.contains(GLFW_KEY_S)) {
            position.z += speed * cos(Math.toRadians(yaw.toDouble())).toFloat()
            position.x += speed * sin(Math.toRadians(yaw.toDouble())).toFloat()
        }
        if (keys.contains(GLFW_KEY_A)) {
            position.z += speed * sin(Math.toRadians(yaw.toDouble())).toFloat()
            position.x -= speed * cos(Math.toRadians(yaw.toDouble())).toFloat()
        }
        if (keys.contains(GLFW_KEY_D)) {
            position.z -= speed * sin(Math.toRadians(yaw.toDouble())).toFloat()
            position.x += speed * cos(Math.toRadians(yaw.toDouble())).toFloat()
        }
        if (keys.contains(GLFW_KEY_TAB)) {
            isTabPressed = true
        }

    }
}


fun main() {

    initAudio()
    // Инициализация GLFW
    if (!glfwInit()) {
        throw IllegalStateException("Не удалось инициализировать GLFW")
    }

    val window = glfwCreateWindow(800, 600, "Endless Strike", 0, 0)
    if (window == 0L) {
        throw RuntimeException("Не удалось создать окно")
    }

    // Центрирование окна на экране
    val videoMode = glfwGetVideoMode(glfwGetPrimaryMonitor())!!
    glfwSetWindowPos(
        window,
        (videoMode.width() - 800) / 2,
        (videoMode.height() - 600) / 2
    )


    glfwMakeContextCurrent(window)
    createCapabilities()


    // Установка параметров OpenGL
    glEnable(GL_DEPTH_TEST)
    glMatrixMode(GL_PROJECTION)
    glLoadIdentity()

    // Устанавливаем перспективу вручную
    val projectionMatrix = Matrix4f().perspective(Math.toRadians(45.0).toFloat(), 800f / 600f, 0.1f, 10000.0f)
    MemoryStack.stackPush().use { stack ->
        val projectionBuffer: FloatBuffer = stack.mallocFloat(16)
        projectionMatrix.get(projectionBuffer)
        glLoadMatrixf(projectionBuffer)
    }

    glMatrixMode(GL_MODELVIEW)


    // Загрузка модели
    val modelRenderer = ModelRenderer("Model/Cube.obj")


    // Загрузка оружия
    val weaponPaths = listOf(
        "Model/weapon/weapon1.fbx",
        "Model/weapon/weapon2.fbx",
        "Model/weapon/weapon3.fbx"
    )
    val skyboxTexturePath = "Model/map/sky/sky3.png"
    val skyboxModelPath = "Model/map/sky/sky2.fbx"
    val skyboxRenderer = Skybox(skyboxTexturePath, skyboxModelPath)
    val weaponRenderer = WeaponRenderer(weaponPaths)
    val mapRenderer = Map("Model/map/Map1.fbx")
    val textRenderer = TextRenderer("Font/Pxll.ttf", 72f)
    val mainRenderer = TextRenderer("Font/Minecraft.ttf", 48f)



    // Камера
    val camera = Camera(Vector3f(0f, 0f, 0f), 0f, 0f, Vector3f(0f, 1f, 0f), Vector3f(0f, 0f, 0f))
    val keys = mutableSetOf<Int>()

    // Настройка обработки событий клавиатуры
    glfwSetKeyCallback(window) { _, key, _, action, _ ->
        if (action == GLFW_PRESS) {
            keys.add(key)
            if(!inMainMenu) {
                when (key) {
                    GLFW_KEY_1 -> weaponRenderer.switchWeapon(0) // Переключение на первое оружие
                    GLFW_KEY_2 -> weaponRenderer.switchWeapon(1) // Переключение на второе оружие
                    GLFW_KEY_3 -> weaponRenderer.switchWeapon(2) // Переключение на третье оружие
                    GLFW_KEY_F1 -> isVsync = !isVsync
                    GLFW_KEY_F2 -> isSky = !isSky
                }
            }
            when (key) {
                GLFW_KEY_ESCAPE -> {
                    val correctID = curID
                    val correctIP = ipAddress
                    curID = -1
                    ipAddress = ""
                    player.isReady = false
                    player.isDead = false
                    if(!inMainMenu or (menuState == MenuState.FINISHED && inMainMenu)) {
                        CoroutineScope(Dispatchers.IO).launch {
                            disconnect(client, correctIP, correctID)
                        }
                    }
                    weaponRenderer.currentWeaponIndex = 0
                    autoAmo = 5
                    drobAmo = 5
                    players.clear()
                    userInput = ""
                    inMainMenu = true
                    menuState = MenuState.ENTER_NICKNAME
                }
            }

        } else if (action == GLFW_RELEASE) {
            keys.remove(key)
        }
    }

    glfwSetMouseButtonCallback(window) { _, button, action, _ ->
        if (button == GLFW_MOUSE_BUTTON_LEFT && action == GLFW_PRESS) {
            if(!inMainMenu) {
                isleftPress = true
                weaponRenderer.startRotation(camera) // Запускаем вращение при нажатии левой кнопки мыши
            }
        } else if (button == GLFW_MOUSE_BUTTON_LEFT && action == GLFW_RELEASE){
            if(!inMainMenu)
                isleftPress = false
        }
    }


    glfwSetScrollCallback(window) { window, xoffset, yoffset ->
        if (yoffset > 0) {
            var go = weaponRenderer.currentWeaponIndex
            if(go == 0 && drobAmo == 0)
                go = 2
            else if(go == 1 && autoAmo == 0)
                go = 0
            else {
                go++
                if (go > 2)
                    go = 0
            }
            weaponRenderer.switchWeapon(go)
        } else {
            var go = weaponRenderer.currentWeaponIndex
            if(go == 0 && autoAmo == 0)
                go = 2
            if(go == 2 && drobAmo == 0)
                go = 0
            else {
                go--
                if (go < 0)
                    go = 2
            }
            if(!inMainMenu)
                weaponRenderer.switchWeapon(go)
        }
    }

    // Настройка обработки событий мыши
    glfwSetCursorPosCallback(window) { _, xpos, ypos ->
        if (firstMouse) {
            lastX = xpos
            lastY = ypos
            firstMouse = false
        }
        val xOffset = xpos - lastX
        val yOffset = lastY - ypos // Инвертируем Y
        lastX = xpos
        lastY = ypos

        if(!inMainMenu) {
            camera.yaw -= (xOffset * sensitivity).toFloat()
            camera.pitch += (yOffset * sensitivity).toFloat()
        }

        // Ограничиваем угол наклона камеры
        if (camera.pitch > 89.0f) camera.pitch = 89.0f
        if (camera.pitch < -89.0f) camera.pitch = -89.0f

        // Сброс позиции мыши в центр окна
        //glfwSetCursorPos(window, 400.0, 300.0)
    }


    var frameCount = 0
    var lastTime = System.nanoTime()
    var lastTime1 = System.nanoTime()
    var fps = 0

    glfwSetInputMode(window, GLFW_CURSOR, GLFW_CURSOR_DISABLED)

    lightning()

    playSound(loadSound("Sound/Music.ogg"), loop = true)

    // Главный цикл
    while (!glfwWindowShouldClose(window)) {
        glfwPollEvents()

        // Если в главном меню
        if (inMainMenu) {

            glfwSetInputMode(window, GLFW_CURSOR, GLFW_CURSOR_NORMAL)
            processMenuInput(window, keys)
            renderMainMenu(window, textRenderer, videoMode.width(), videoMode.height())
            if(players.size > 1 && curID != -1) {
                CoroutineScope(Dispatchers.IO).launch {
                    if (isallReady) {
                        var PlaceID = -1
                        players.forEachIndexed { index, value ->
                            if(value.id == curID) {
                                PlaceID = index
                            }
                        }
                        inMainMenu = false
                        when (PlaceID) {
                            0 -> {
                                camera.position.x = 0f
                                camera.position.z = 0f
                                camera.yaw = -90f
                                camera.pitch = 0f
                            }

                            1 -> {
                                camera.position.x = 0f
                                camera.position.z = -15f
                                camera.yaw = -90f
                                camera.pitch = 0f
                            }

                            2 -> {
                                camera.position.x = 20f
                                camera.position.z = 0f
                                camera.yaw = 90f
                                camera.pitch = 0f
                            }

                            3 -> {
                                camera.position.x = 20f
                                camera.position.z = -15f
                                camera.yaw = 90f
                                camera.pitch = 0f
                            }
                        }
                    }
                    weaponRenderer.currentWeaponIndex = 0
                }
            }
        } else {
            glfwSetInputMode(window, GLFW_CURSOR, GLFW_CURSOR_DISABLED)
            glClear(GL_COLOR_BUFFER_BIT or GL_DEPTH_BUFFER_BIT)
            glClearColor(0.0f, 1.0f, 1.0f, 0.0f)
            glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA)

            val currentTime = System.nanoTime()
            val deltaTime = (currentTime - lastTime) / 1_000_000_000f // Расчет времени в секундах
            lastTime = currentTime
            camera.updateMovement(deltaTime, keys)

            glLoadIdentity()
            // Установка матрицы модели для камеры
            glRotatef(camera.pitch, -1f, 0f, 0f)
            glRotatef(camera.yaw, 0f, -1f, 0f)
            glTranslatef(-camera.position.x, -camera.position.y, -camera.position.z)

            if (isVsync)
                glfwSwapInterval(1) // 1 - включает V-Sync
            else
                glfwSwapInterval(0) // 1 - включает V-Sync


            if (weaponRenderer.currentWeaponIndex == 2 && isleftPress) {
                weaponRenderer.startRotation(camera)
            }


            mapRenderer.render()


            if (isSky)
                skyboxRenderer.render(camera)
            weaponRenderer.render(camera, deltaTime) // Отрисовка оружия, привязанного к камере


            textRenderer.renderText(
                "Health: $hp",
                videoMode.width() / 2 -40f,
                videoMode.height() - 100f,
                videoMode.width(),
                videoMode.height()
            ) // Позиция в правом нижнем углу
            textRenderer.renderText(
                "FPS: $fps",
                100f,
                100f,
                videoMode.width(),
                videoMode.height()
            ) // Позиция в правом нижнем углу
            mainRenderer.renderText(
                "AMMO: $curAmmo",
                videoMode.width() - 300f,
                videoMode.height() - 100f,
                videoMode.width(),
                videoMode.height()
            )
            mainRenderer.renderText(
                nickname,
                videoMode.width() - 300f,
                100f,
                videoMode.width(),
                videoMode.height()
            )
            renderPlayerStats(players, mainRenderer, videoMode.width(), videoMode.height())

            if(players.size > 1 && curID !=- 1) {
                 CoroutineScope(Dispatchers.IO).launch {
                     if (isallDeath) {
                         var PlaceID = -1
                         players.forEachIndexed { index, value ->
                             if(value.id == curID) {
                                 PlaceID = index
                             }
                         }
                         when (PlaceID) {
                             0 -> {
                                 camera.position.x = 0f
                                 camera.position.z = 0f
                                 camera.yaw = -90f
                                 camera.pitch = 0f
                             }

                             1 -> {
                                 camera.position.x = 0f
                                 camera.position.z = -15f
                                 camera.yaw = -90f
                                 camera.pitch = 0f
                             }

                             2 -> {
                                 camera.position.x = 20f
                                 camera.position.z = 0f
                                 camera.yaw = 90f
                                 camera.pitch = 0f
                             }

                             3 -> {
                                 camera.position.x = 20f
                                 camera.position.z = -15f
                                 camera.yaw = 90f
                                 camera.pitch = 0f
                             }
                         }
                     }
                 }
             }

            players.forEach { player ->
                if(player.id != curID && !player.isDead) {
                    glTranslatef(player.positionX, player.positionY, player.positionZ)
                    //glRotatef(player.pitch, -1f, 0f, 0f)
                    glRotatef(player.yaw-180, 0f, 1f, 0f)
                    modelRenderer.render()
                }
                else
                    hp = player.hp
            }
        }
        players.forEach { player ->
            isallDeath = player.isAllDead
            isallReady = player.isAllReady
        }
        player.isAllReady = isallReady
        player.isAllDead = isallDeath
        player.hp = hp
        player.ammo = curAmmo
        player.currentWeapon = weaponRenderer.currentWeaponIndex
        player.yaw=camera.yaw
        player.pitch=camera.pitch
        player.positionX = camera.position.x
        player.positionY = camera.position.y
        player.positionZ = camera.position.z

        glfwSwapBuffers(window)
        glfwPollEvents()

        // Расчет FPS
        frameCount++
        if (System.nanoTime() - lastTime1 >= 1_000_000_000) {
            fps = frameCount
            frameCount = 0
            lastTime1 = System.nanoTime()
        }
    }
    CoroutineScope(Dispatchers.IO).launch {
        disconnect(client, ipAddress, curID)
    }
    closeAudio()
    glfwDestroyWindow(window)
    glfwTerminate()
}
