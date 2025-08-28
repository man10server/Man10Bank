package red.man10.man10bank.db

import org.bukkit.plugin.java.JavaPlugin
import org.ktorm.database.Database

// Test double for DatabaseProvider: use in-memory H2 instead of MySQL
object DatabaseProvider {
    @Volatile
    private var db: Database? = null

    fun init(plugin: JavaPlugin) {
        if (db != null) return
        db = Database.connect(
            url = "jdbc:h2:mem:man10bank_test;MODE=MySQL;DB_CLOSE_DELAY=-1",
            driver = "org.h2.Driver",
        )
        // Create minimal schema needed by plugin
        db!!.useConnection { c ->
            c.createStatement().use { st ->
                st.addBatch(
                    """
                    CREATE TABLE IF NOT EXISTS user_bank (
                      id INT AUTO_INCREMENT PRIMARY KEY,
                      player VARCHAR(16),
                      uuid VARCHAR(36),
                      balance DECIMAL
                    );
                    """.trimIndent()
                )
                st.addBatch(
                    """
                    CREATE TABLE IF NOT EXISTS money_log (
                      id INT AUTO_INCREMENT PRIMARY KEY,
                      player VARCHAR(16),
                      uuid VARCHAR(36),
                      plugin_name VARCHAR(16),
                      amount DECIMAL,
                      note VARCHAR(64),
                      display_note VARCHAR(64),
                      server VARCHAR(16),
                      deposit BOOLEAN,
                      date TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                    );
                    """.trimIndent()
                )
                st.executeBatch()
            }
        }
    }

    fun database(): Database = db ?: error("Test Database is not initialized")

    fun isInitialized(): Boolean = db != null
}

