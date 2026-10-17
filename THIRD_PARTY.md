# Third-party components

The plugin JAR includes relocated PostgreSQL JDBC 42.7.14 (BSD 2-Clause),
MariaDB Connector/J 3.5.10 (LGPL 2.1 or later), and Checker Framework qualifier
annotations (MIT). Their license and notice resources are retained in the JAR.

Paper, Adventure, VaultAPI and PlaceholderAPI are compile-time provided APIs;
their implementations are not bundled in PlanetryCore. The runtime smoke
fixtures and downloaded server/PAPI JARs are test-only.

MariaDB Connector/J source matching the bundled binary is included in every
release as `mariadb-java-client-3.5.10-sources.jar`. Its upstream source and build
instructions are at https://github.com/mariadb-corporation/mariadb-connector-j.
PostgreSQL source is at https://github.com/pgjdbc/pgjdbc.

To replace or modify a driver, obtain the corresponding source, build/install
that version using its upstream instructions, update its dependency version in
`pom.xml`, and run `mvn clean verify`. Maven Shade relocates the classes and JDBC
service metadata and packages the replacement. You may modify the LGPL library
and rebuild/relink this plugin for your use; preserve applicable notices and
provide the matching source when redistributing it.
