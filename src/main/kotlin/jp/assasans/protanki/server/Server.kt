package jp.assasans.protanki.server

import kotlin.coroutines.coroutineContext
import kotlin.random.Random
import kotlin.reflect.KClass
import kotlinx.coroutines.launch
import mu.KotlinLogging
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import org.reflections.Reflections
import org.reflections.scanners.Scanners
import jp.assasans.protanki.server.battles.Battle
import jp.assasans.protanki.server.battles.BattleProperty
import jp.assasans.protanki.server.battles.BattleTeam
import jp.assasans.protanki.server.battles.IBattleProcessor
import jp.assasans.protanki.server.battles.bonus.BattleGoldBonus
import jp.assasans.protanki.server.battles.bonus.BattleNitroBonus
import jp.assasans.protanki.server.battles.map.IMapRegistry
import jp.assasans.protanki.server.battles.map.get
import jp.assasans.protanki.server.battles.mode.DeathmatchModeHandler
import jp.assasans.protanki.server.chat.*
import jp.assasans.protanki.server.client.sendBattleChat
import jp.assasans.protanki.server.client.sendChat
import jp.assasans.protanki.server.commands.ICommandHandler
import jp.assasans.protanki.server.commands.ICommandRegistry
import jp.assasans.protanki.server.extensions.cast
import jp.assasans.protanki.server.garage.IGarageMarketRegistry
import jp.assasans.protanki.server.math.Quaternion
import jp.assasans.protanki.server.math.nextVector3
import jp.assasans.protanki.server.resources.IResourceServer

class Server : KoinComponent {
  private val logger = KotlinLogging.logger { }

  private val socketServer by inject<ISocketServer>()
  private val resourceServer by inject<IResourceServer>()
  private val commandRegistry by inject<ICommandRegistry>()
  private val battleProcessor by inject<IBattleProcessor>()
  private val marketRegistry by inject<IGarageMarketRegistry>()
  private val mapRegistry by inject<IMapRegistry>()
  private val chatCommandRegistry by inject<IChatCommandRegistry>()

  suspend fun run() {
    logger.info { "Server started" }

    mapRegistry.load()
    marketRegistry.load()

    val reflections = Reflections("jp.assasans.protanki.server")

    reflections.get(Scanners.SubTypes.of(ICommandHandler::class.java).asClass<ICommandHandler>()).forEach { type ->
      val handlerType = type.kotlin.cast<KClass<ICommandHandler>>()

      commandRegistry.registerHandlers(handlerType)
      logger.debug { "Registered command handler: ${handlerType.simpleName}" }
    }

    battleProcessor.battles.add(
      Battle(
        coroutineContext,
        id = "493202bf695cc88a",
        title = "ProTanki Server",
        map = mapRegistry.get("map_kungur", ServerMapTheme.SummerDay),
        modeHandlerBuilder = DeathmatchModeHandler.builder()
      )
    )

    chatCommandRegistry.apply {
      command("help") {
        description("Show list of commands or help for a specific command")

        argument("command", String::class) {
          description("Command to show help for")
          optional()
        }

        handler {
          val commandName: String? = arguments.getOrNull("command")
          if(commandName == null) {
            socket.sendChat("Available commands: ${commands.joinToString(", ") { command -> command.name }}")
            return@handler
          }

          val command = commands.singleOrNull { command -> command.name == commandName }
          if(command == null) {
            socket.sendChat("Unknown command: $commandName")
            return@handler
          }

          val builder: StringBuilder = StringBuilder()

          builder.append(command.name)
          if(command.description != null) {
            builder.append(" - ${command.description}")
          }
          builder.append("\n")

          if(command.arguments.isNotEmpty()) {
            builder.appendLine("Arguments:")
            command.arguments.forEach { argument ->
              builder.append("    ")
              builder.append("${argument.name}: ${argument.type.simpleName}")
              if(argument.isOptional) builder.append(" (optional)")
              if(argument.description != null) {
                builder.append(" - ${argument.description}")
              }
              builder.appendLine()
            }
          }

          socket.sendChat(builder.toString())
        }
      }

      command("kick") {
        description("Kick a user from the server")

        argument("user", String::class) {
          description("The user to kick")
        }

        handler {
          val username: String = arguments["user"]
          val player = socketServer.players.singleOrNull { socket -> socket.user?.username == username }
          if(player == null) {
            socket.sendChat("User '$username' not found")
            return@handler
          }

          player.deactivate()
          if(player != socket) {
            socket.sendChat("User '$username' has been kicked")
          }
        }
      }

      command("dump") {
        subcommand("battle") {
          handler {
            val battle = socket.battle
            if(battle == null) {
              socket.sendChat("You are not in a battle")
              return@handler
            }

            val builder = StringBuilder()

            builder.appendLine("Battle:")
            builder.appendLine("    ID: ${battle.id}")
            builder.appendLine("    Name: ${battle.title}")
            builder.appendLine("Map:")
            builder.appendLine("    ID: ${battle.map.id}")
            builder.appendLine("    Name: ${battle.map.name}")
            builder.appendLine("    Theme: ${battle.map.theme.name}")
            builder.appendLine("Players:")
            battle.players.forEach { player ->
              builder.append("    - ${player.user.username}")

              val properties = mutableListOf<String>()

              properties.add("load stage: ${player.loadState}")

              if(player.team != BattleTeam.None) {
                properties.add("team: ${player.team.name}")
              }
              properties.add("score: ${player.score}")
              properties.add("kills: ${player.kills}")
              properties.add("deaths: ${player.deaths}")

              if(properties.isNotEmpty()) {
                builder.append(" (${properties.joinToString(", ")})")
              }
              builder.append("\n")

              val tank = player.tank
              if(tank != null) {
                builder.appendLine("        Tank: ${tank.id}/${tank.incarnation} (${tank.state})")
                builder.appendLine("            Position: ${tank.position}")
                builder.appendLine("            Orientation: ${tank.orientation}")
              }
            }
            builder.appendLine("Handler:")
            builder.appendLine("    Class: ${battle.modeHandler::class.simpleName}")
            builder.appendLine("    Mode: ${battle.modeHandler.mode.name}")
            battle.modeHandler.dump(builder)

            socket.sendBattleChat(builder.toString())
          }
        }
      }

      command("property") {
        subcommand("list") {
          handler {
            val battle = socket.battle
            if(battle == null) {
              socket.sendChat("You are not in a battle")
              return@handler
            }

            val builder = StringBuilder()
            BattleProperty.values().forEach { property ->
              val value = battle.properties[property]

              builder.append("${property.key}: $value")
              if(property.defaultValue != null) {
                builder.append(" (default: ${property.defaultValue})")
              }
              builder.append("\n")
            }

            socket.sendBattleChat(builder.toString())
          }
        }

        subcommand("get") {
          argument("property", String::class) {
            description("The battle property key to get")
          }

          handler {
            val key: String = arguments["property"]

            val battle = socket.battle
            if(battle == null) {
              socket.sendChat("You are not in a battle")
              return@handler
            }

            val builder = StringBuilder()

            val property = BattleProperty.getOrNull(key)
            if(property == null) {
              socket.sendBattleChat("No such property: $key")
              return@handler
            }

            val value = battle.properties[property]
            builder.append("${property.key}: $value")
            if(property.defaultValue != null) {
              builder.append(" (default: ${property.defaultValue})")
            }

            socket.sendBattleChat(builder.toString())
          }
        }

        subcommand("set") {
          argument("property", String::class) {
            description("The battle property key to set")
          }

          argument("value", String::class) {
            description("The value to set the property to")
          }

          handler {
            val key: String = arguments["property"]
            val value: String = arguments["value"]

            val battle = socket.battle
            if(battle == null) {
              socket.sendChat("You are not in a battle")
              return@handler
            }

            val builder = StringBuilder()

            val property = BattleProperty.getOrNull(key)
            if(property == null) {
              socket.sendBattleChat("No such property: $key")
              return@handler
            }

            val oldValue = battle.properties[property]

            val typedValue: Any = when(property.type) {
              String::class  -> value
              Int::class     -> value.toInt()
              Double::class  -> value.toDouble()
              Boolean::class -> when {
                value.equals("false", ignoreCase = true) -> false
                value.equals("true", ignoreCase = true)  -> true
                else                                     -> throw Exception("Invalid Boolean value: $value")
              }

              else           -> throw Exception("Unsupported property type: ${property.type.qualifiedName}")
            }

            battle.properties.setValue(property, typedValue)
            builder.append("Changed $key: $oldValue -> $typedValue")

            socket.sendBattleChat(builder.toString())
          }
        }
      }

      command("bonus") {
        description("Manage battle bonuses")

        subcommand("spawn") {
          description("Spawn a bonus at random point")

          argument("type", String::class) {
            description("The type of bonus to spawn")
          }

          handler {
            val type: String = arguments["type"]

            val battle = socket.battle
            if(battle == null) {
              socket.sendChat("You are not in a battle")
              return@handler
            }

            val bonusType = BonusType.get(type)
            if(bonusType == null) {
              socket.sendBattleChat("No such bonus: $type")
              return@handler
            }

            val bonusPoint = battle.map.bonuses
              .filter { bonus -> bonus.types.contains(bonusType) }
              .filter { bonus -> bonus.modes.contains(battle.modeHandler.mode) }
              .random()

            val position = Random.nextVector3(bonusPoint.position.min.toVector(), bonusPoint.position.max.toVector())
            val rotation = Quaternion()
            rotation.fromEulerAngles(bonusPoint.rotation.toVector())

            val bonus = when(bonusType) {
              BonusType.Nitro -> BattleNitroBonus(battle, battle.bonusProcessor.nextId, position, rotation)
              BonusType.Gold  -> BattleGoldBonus(battle, battle.bonusProcessor.nextId, position, rotation)
              else            -> throw Exception("Unsupported bonus type: $bonusType")
            }

            battle.bonusProcessor.incrementId()
            battle.coroutineScope.launch {
              battle.bonusProcessor.spawn(bonus)
              socket.sendBattleChat("Spawned $bonusType at $position, $rotation (with parachute: ${bonusPoint.parachute})")
            }
          }
        }
      }
    }

    HibernateUtils.createEntityManager().close() // Initialize database

    resourceServer.run()
    socketServer.run()
  }
}
