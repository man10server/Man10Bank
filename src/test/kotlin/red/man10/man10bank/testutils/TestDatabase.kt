package red.man10.man10bank.testutils

import org.ktorm.database.Database

object TestDatabase {
    fun create(name: String = "bank", withSchema: Boolean = true): Database {
        val db = Database.connect(
            url = "jdbc:h2:mem:${name};MODE=MySQL;DB_CLOSE_DELAY=-1",
            driver = "org.h2.Driver",
        )
        if (withSchema) createSchema(db)
        return db
    }

    fun createSchema(db: Database) {
        db.useConnection { c ->
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
}

