package org.opensearch.replication.sanity


import org.apache.logging.log4j.LogManager
import org.junit.BeforeClass
import org.opensearch.client.ResponseException
import org.opensearch.replication.MultiClusterRestTestCase
import org.opensearch.replication.StartReplicationRequest
import org.opensearch.replication.startReplication
import org.assertj.core.api.Assertions.assertThatThrownBy


const val FOLLOWER = "bwcFollower"
const val FOLLOWER_INDEX = "bwc_test_index"
const val CONNECTION_NAME = "bwc_connection"


class BasicSanityIT : MultiClusterRestTestCase() {
    private val clusterSuffix = System.getProperty("tests.cluster_suffix")
    private val followerName = "${FOLLOWER}$clusterSuffix"

    companion object {

        private val log = LogManager.getLogger(BasicSanityIT::class.java)

        @BeforeClass
        @JvmStatic
        fun setupTestClusters() {
            val suffix = System.getProperty("tests.cluster_suffix")
            val follower = "${FOLLOWER}$suffix"
            val clusters = HashMap<String, TestCluster>()
            clusters.put(follower, createTestCluster(follower, true, true, true, false))
            testClusters = clusters
        }
    }

    fun `basic sanity start replication test`() {
        log.error("Hello Naveen 1")
        val follower = getClientForCluster(followerName)
        log.error("Hello Naveen 2 " + follower)
        try {
            assertThatThrownBy {
                follower.startReplication(
                    StartReplicationRequest(CONNECTION_NAME, FOLLOWER_INDEX, FOLLOWER_INDEX),
                    waitForRestore = true
                )
            }.isInstanceOf(ResponseException::class.java)
        } finally {
            log.error("Hello Naveen 3 ")
        }
    }

}

