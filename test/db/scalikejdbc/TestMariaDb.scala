package db.scalikejdbc

import org.testcontainers.utility.DockerImageName

/** The MariaDB every test container runs: production's version, the Ubuntu 24.04
  * package's (1:10.11.14-0ubuntu0.24.04.1). */
object TestMariaDb {
  val image: DockerImageName = DockerImageName.parse("mariadb:10.11.14")
}
