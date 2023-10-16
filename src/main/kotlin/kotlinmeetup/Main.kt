package kotlinmeetup

import com.typesafe.config.Config
import com.typesafe.config.ConfigFactory
import com.zaxxer.hikari.HikariDataSource
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotliquery.Row
import kotliquery.queryOf
import kotliquery.sessionOf
import org.flywaydb.core.Flyway
import org.slf4j.LoggerFactory
import javax.sql.DataSource
import kotlin.reflect.full.declaredMemberProperties

private val log = LoggerFactory.getLogger("kotlinmeetup.Main")

fun main() {
    log.info("Hello, World!")

    val environment = System.getenv("KOTLINMEETUP_ENV") ?: "local"
    val config = createConfig(environment)

    val secretsRegex = "password|secret|key"
        .toRegex(RegexOption.IGNORE_CASE)

    log.info("Applocation config: ${WebappConfig::class.declaredMemberProperties
        .sortedBy { it.name }
        .map {
            if (secretsRegex.containsMatchIn(it.name)) {
                "${it.name} = ${it.get(config).toString().take(2)}********"
            } else {
                "${it.name} = ${it.get(config)}"
            }
        }
        .joinToString(separator = "\n")}")

    val dataSource = createDataSource(config)

    Flyway.configure()
        .dataSource(dataSource)
        .locations("db/migrations")
        .table("flyway_something_something")
        .load()
        .migrate()

    dataSource.getConnection().use { conn ->
        conn.createStatement().use { stmt ->
            stmt.executeQuery("SELECT 1")
        }
    }

    embeddedServer(Netty, port = config.httpPort) {
        this.createKtorApplicaiton(dataSource = dataSource)
    }.start(wait = true)
}

data class WebappConfig(
    val httpPort: Int,
    val dbUrl: String,
    val dbUsername: String,
    val dbPassword: String
)

fun createConfig(environment: String): WebappConfig {
    val config = ConfigFactory
        .parseResources("app-${environment}.conf")
        .withFallback(ConfigFactory.parseResources("app.conf"))
        .resolve()

    return WebappConfig(
        httpPort = config.getInt("httpPort"),
        dbUrl = config.getString("dbUrl"),
        dbUsername = config.getString("dbUsername"),
        dbPassword = config.getString("dbPassword")
    )
}

fun createDataSource(config: WebappConfig) = HikariDataSource().apply {
    jdbcUrl = config.dbUrl
    username = config.dbUsername
    password = config.dbPassword
}

fun Application.createKtorApplicaiton(dataSource: DataSource) {
    install(StatusPages) {
        exception<Throwable>() { call, cause ->
            kotlinmeetup.log.error("An unknown error occurred", cause)

            call.respondText(
                text = "500: $cause",
                status = HttpStatusCode.InternalServerError
            )
        }
    }

    routing {
        get("/") {
            println(sessionOf(dataSource).use { dbSess ->
                dbSess.single(queryOf("select 1"), ::mapFromRow)
            })
            call.respondText("Hello, World!")
        }
    }
}

// Map<String, Any?>
fun mapFromRow(row: Row): Any? {
    return row.underlying.metaData
        .let { (1..it.columnCount).map(it::getColumnName) }
        .map { it to row.anyOrNull(it) }
        .toMap()
}