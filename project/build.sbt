resolvers += "Radlab Repository" at "http://scads.knowsql.org/nexus/content/groups/public/"

resolvers += Classpaths.typesafeResolver

addSbtPlugin("com.typesafe.sbt" % "sbt-ghpages" % "0.5.0")

//addSbtPlugin("com.eed3si9n" % "sbt-assembly" % "0.7.2", scalaVersion = "2.9.1", sbtVersion = "0.11.2")
