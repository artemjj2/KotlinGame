import io.ktor.server.application.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.*
import kotlinx.serialization.Serializable
import org.joml.Vector3f

var isGame = false
var isAllDead = false

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

val players = mutableListOf<Player>()

fun main() {
    embeddedServer(Netty, port = 8080) {
        configureRouting()
        configureSerialization()
    }.start(wait = true)
}

fun Application.configureRouting() {
    routing {
        post("/connect") {
            if ((players.size >= 4) or (isGame)) {
                call.respondText("Сервер полон", status = io.ktor.http.HttpStatusCode.Forbidden)
                return@post
            }
            val newPlayer = call.receive<Player>()
            var assignedId = -1
            if (players.size > 0)
                assignedId = players[players.size - 1].id + 1
            else
                assignedId = 0

            val playerWithId = newPlayer.copy(id = assignedId)
            println("Получены данные: $playerWithId")
            players.add(playerWithId)
            call.respond(playerWithId.id)
        }

        post("/update") {
            isGame = true
            if(players.size > 1)
                players.forEach{
                    if (!it.isReady)
                        isGame = false
                }
            else
                isGame = false

            var isAlldad = 0
            players.forEach{
                if (!it.isDead)
                    isAlldad++
            }
            if( isAlldad < 2) {
                isAllDead = true
                val scope = CoroutineScope(Dispatchers.Default)
                scope.launch {
                    waitDeath()
                }
            }
            else
                isAllDead = false

            val updatedPlayer = call.receive<Player>()
            for (i in 0..players.size-1){
                if(players[i].id == updatedPlayer.id)
                    players[i] = updatedPlayer.copy(hp = players[i].hp, kills = players[i].kills, deaths = players[i].deaths, isDead = players[i].isDead, isAllDead = isAllDead , isAllReady = isGame)
            }
            call.respond(players)
        }

        post("/disconnect") {
            val playerCurId = call.receive<Int>()
            launch {
                var playerId = -1
                for (i in 0..players.size-1){
                    if(players[i].id == playerCurId)
                        playerId = i
                }
                if (playerId in players.indices) {
                    players.removeAt(playerId)
                    call.respondText("Игрок $playerId отключен")
                } else {
                    call.respondText("Игрок не найден", status = io.ktor.http.HttpStatusCode.BadRequest)
                }
            }
        }

        post("/damage") {
            val damageRequest = call.receive<DamageRequest>()
            var attackerID = -1
            var targetID = -1
            for (i in 0..players.size - 1) {
                if(players[i].id == damageRequest.attackerId)
                    attackerID = i
                if(players[i].id == damageRequest.targetId)
                    targetID = i
            }
            val attacker = players.getOrNull(attackerID)
            val target = players.getOrNull(targetID)

            if (attacker == null || target == null) {
                call.respondText("Игрок не найден", status = io.ktor.http.HttpStatusCode.BadRequest)
                return@post
            }

            target.hp = (target.hp - damageRequest.damage).coerceAtLeast(0)
            if(target.hp == 0){
                target.isDead = true
                target.deaths++
                attacker.kills++
            }
            print("Игрок ${target.nickname} udaril ${attacker.nickname} получил ${damageRequest.damage} урона. HP: ${target.hp}")
            call.respondText("Игрок ${target.nickname} получил ${damageRequest.damage} урона. HP: ${target.hp}")
        }

    }
}

suspend fun waitDeath() {
    delay(1500)
    for (i in 0..players.size - 1) {
        players[i] = players[i].copy(hp = 100)
        players[i].isDead = false
    }
}

fun Application.configureSerialization() {
    install(ContentNegotiation) {
        json()
    }
}
