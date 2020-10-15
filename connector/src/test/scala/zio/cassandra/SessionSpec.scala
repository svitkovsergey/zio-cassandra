package zio.cassandra

import java.net.InetSocketAddress
import java.time.Instant

import com.datastax.oss.driver.api.core.cql.{ BatchStatement, BoundStatement, DefaultBatchType }
import com.dimafeng.testcontainers.CassandraContainer
import com.typesafe.config.ConfigFactory
import wvlet.log.{ LogLevel, LogSupport, Logger }
import zio.{ blocking => _, test => _, _ }
import zio.cassandra.service.CassandraSession
import zio.container.ZTestContainer
import zio.test.{ DefaultRunnableSpec, _ }
import zio.test.Assertion._

object SessionSpec extends DefaultRunnableSpec with LogSupport with Fixtures {
  Logger.setDefaultLogLevel(LogLevel.INFO)

  implicit class toJavaInt(val i: Int) extends AnyVal {
    def asJava: Integer = i.asInstanceOf[java.lang.Integer]
  }

  override def spec =
    suite("Cassandra session")(
      testM("complete scenario with multiple queries")(
        for {
          session     <- ZIO.service[service.CassandraSession]
          insert      <- session.prepare(insertQuery)
          update      <- session.prepare(updateQuery)
          delete      <- session.prepare(deleteQuery)
          select      <- session.prepare(selectQuery)
          emptyResult <- session.bind(select, Seq("user1")) >>= session.selectOne
          preparedBatchSeq <- ZIO.collectAll(
                               0.until(10) map (i =>
                                 session.bind(insert, Seq("user1", i.asJava, i.toString, Instant.now()))
                               )
                             )
          _         <- executeBatch(preparedBatchSeq)
          _         <- session.bindAndExecute(insert, Seq("user1", 11.asJava, 21.toString, Instant.now()))
          _         <- session.bindAndExecute(insert, Seq("user1", 12.asJava, 22.toString, Instant.now()), "slow")
          _         <- session.bind(update, Seq("nope", "user1", 2.asJava)) >>= session.execute
          _         <- session.bind(delete, Seq("user1", 1.asJava)) >>= session.execute
          selectAll <- session.bind(select, Seq("user1")) >>= session.selectAll
        } yield {
          assert(emptyResult)(isNone) &&
          assert(selectAll.size)(equalTo(11)) &&
          assert(
            selectAll
              .find(r => r.getInt("seq_nr") == 2)
              .map(_.getString("data"))
          )(isSome(equalTo("nope")))
        }
      ),
      testM("selectAll should be reference transparent")(
        for {
          session      <- ZIO.service[service.CassandraSession]
          select       <- session.prepare(selectQuery)
          effect       = (session.bind(select, Seq("user1")) >>= session.selectAll).map(_.map(_.getString(0)))
          resultOne    <- effect
          resultSecond <- effect
        } yield {
          assert(resultOne)(equalTo(resultSecond))
        }
      ),
      testM("select should be reference transparent")(
        for {
          session      <- ZIO.service[service.CassandraSession]
          select       <- session.prepare(selectQuery)
          statement    <- session.bind(select, Seq("user1"))
          stream       = session.select(statement).map(_.getString(0))
          resultOne    <- stream.runCollect
          resultSecond <- stream.runCollect
        } yield {
          assert(resultOne)(equalTo(resultSecond))
        }
      )
    ).provideCustomLayerShared(layer)
}

trait Fixtures {
  val keyspace = "test_keyspace"

  val keyspaceQuery =
    s"""CREATE KEYSPACE IF NOT EXISTS $keyspace
       |  WITH REPLICATION =
       |    {'class' : 'SimpleStrategy', 'replication_factor' : 1}""".stripMargin

  val table = "test_table"

  val tableQuery =
    s"""CREATE TABLE IF NOT EXISTS $keyspace.$table(
       |user_id text,
       |seq_nr int,
       |data text,
       |created_at timestamp,
       |PRIMARY KEY (user_id, seq_nr))""".stripMargin

  val insertQuery =
    s"""
       |INSERT INTO $keyspace.$table (user_id, seq_nr, data, created_at) values (?, ?, ?, ?)
       |""".stripMargin

  val updateQuery =
    s"""
       |UPDATE $keyspace.$table SET data = ? WHERE user_id = ? and seq_nr = ?
       |""".stripMargin

  val deleteQuery =
    s"""
       |DELETE FROM $keyspace.$table WHERE user_id = ? and seq_nr = ?
       |""".stripMargin

  val selectQuery =
    s"""
       |SELECT user_id, seq_nr, data, created_at FROM $keyspace.$table WHERE user_id = ?
       |""".stripMargin

  val layaerCassandra = ZTestContainer.cassandra

  val layerSession = (for {
    cassandra <- ZTestContainer[CassandraContainer].toManaged_
    session <- {
      val address = new InetSocketAddress(cassandra.containerIpAddress, cassandra.mappedPort(9042))
      val config  = ConfigFactory.load().getConfig("cassandra.test-driver")
      CassandraSession.make(config, Seq(address))
    }
    _ <- prepareTestSession(session).toManaged_
  } yield session).toLayer.mapError(TestFailure.die)

  val layer = layaerCassandra >+> layerSession

  def prepareTestSession(session: CassandraSession) =
    session.execute(keyspaceQuery) *> session.execute(tableQuery)

  def withSession[R](f: service.CassandraSession => Task[R]): ZIO[Session, Throwable, R] = ZIO.accessM[Session] {
    session => f(session.get)
  }

  def executeBatch(seq: Seq[BoundStatement]): RIO[Session, Unit] = withSession { s =>
    val batch = BatchStatement
      .builder(DefaultBatchType.LOGGED)
      .addStatements(seq: _*)
      .build()
    s.execute(batch).unit
  }

}
