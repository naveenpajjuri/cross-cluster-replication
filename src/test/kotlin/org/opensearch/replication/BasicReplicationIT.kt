/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 *
 * Modifications Copyright OpenSearch Contributors. See
 * GitHub history for details.
 */

package org.opensearch.replication

import org.opensearch.replication.MultiClusterAnnotations.ClusterConfiguration
import org.opensearch.replication.MultiClusterAnnotations.ClusterConfigurations
import org.assertj.core.api.Assertions
import org.assertj.core.api.Assertions.assertThat
import org.opensearch.OpenSearchStatusException
import org.opensearch.action.DocWriteResponse.Result
import org.opensearch.action.admin.indices.forcemerge.ForceMergeRequest
import org.opensearch.action.delete.DeleteRequest
import org.opensearch.action.get.GetRequest
import org.opensearch.action.index.IndexRequest
import org.opensearch.client.RequestOptions
import org.opensearch.client.indices.CreateIndexRequest
import org.opensearch.common.CheckedRunnable
import org.opensearch.test.OpenSearchTestCase.assertBusy
import org.junit.Assert
import java.util.Locale
import java.util.concurrent.TimeUnit

const val LEADER = "leaderCluster"
const val FOLL = "followCluster"

@ClusterConfigurations(
    ClusterConfiguration(clusterName = LEADER),
    ClusterConfiguration(clusterName = FOLL)
)
class BasicReplicationIT : MultiClusterRestTestCase() {
    private val leaderIndexName = "leader_index"
    private val followerIndexName = "follower_index"

    fun `test empty index replication`() {
        val follower = getClientForCluster(FOLL)
        val leader = getClientForCluster(LEADER)
        createConnectionBetweenClusters(FOLL, LEADER)

        val leaderIndex = randomAlphaOfLength(10).toLowerCase(Locale.ROOT)
        val followerIndex = randomAlphaOfLength(10).toLowerCase(Locale.ROOT)
        // Create an empty index on the leader and trigger replication on it
        val createIndexResponse = leader.indices().create(CreateIndexRequest(leaderIndex), RequestOptions.DEFAULT)
        assertThat(createIndexResponse.isAcknowledged).isTrue()
        try {
            follower.startReplication(StartReplicationRequest("source", leaderIndex, followerIndex), waitForRestore=true)

            val source = mapOf("name" to randomAlphaOfLength(20), "age" to randomInt().toString())
            var response = leader.index(IndexRequest(leaderIndex).id("1").source(source), RequestOptions.DEFAULT)
            assertThat(response.result).isEqualTo(Result.CREATED)

            assertBusy({
                val getResponse = follower.get(GetRequest(followerIndex, "1"), RequestOptions.DEFAULT)
                assertThat(getResponse.isExists).isTrue()
                assertThat(getResponse.sourceAsMap).isEqualTo(source)
            }, 60L, TimeUnit.SECONDS)

            // Ensure force merge on leader doesn't impact replication
            for (i in 2..5) {
                response = leader.index(IndexRequest(leaderIndex).id("$i").source(source), RequestOptions.DEFAULT)
                assertThat(response.result).isEqualTo(Result.CREATED)
            }
            leader.indices().forcemerge(ForceMergeRequest(leaderIndex), RequestOptions.DEFAULT)
            for (i in 6..10) {
                response = leader.index(IndexRequest(leaderIndex).id("$i").source(source), RequestOptions.DEFAULT)
                assertThat(response.result).isEqualTo(Result.CREATED)
            }
            assertBusy({
                for (i in 2..10) {
                    val getResponse = follower.get(GetRequest(followerIndex, "$i"), RequestOptions.DEFAULT)
                    assertThat(getResponse.isExists).isTrue()
                    assertThat(getResponse.sourceAsMap).isEqualTo(source)
                }
            }, 60L, TimeUnit.SECONDS)

            // Force merge on follower however isn't allowed due to WRITE block
            Assertions.assertThatThrownBy {
                follower.indices().forcemerge(ForceMergeRequest(followerIndex), RequestOptions.DEFAULT)
            }.isInstanceOf(OpenSearchStatusException::class.java)
                .hasMessage("OpenSearch exception [type=cluster_block_exception, reason=index [$followerIndex] " +
                        "blocked by: [FORBIDDEN/1000/index read-only(cross-cluster-replication)];]")

        } finally {
            follower.stopReplication(followerIndex)
        }
    }

    fun `test existing index replication`() {
        val follower = getClientForCluster(FOLL)
        val leader = getClientForCluster(LEADER)
        createConnectionBetweenClusters(FOLL, LEADER)

        // Create an index with data before commencing replication
        val leaderIndex = randomAlphaOfLength(10).toLowerCase(Locale.ROOT)
        val followerIndex = randomAlphaOfLength(10).toLowerCase(Locale.ROOT)
        val source = mapOf("name" to randomAlphaOfLength(20), "age" to randomInt().toString())
        val response = leader.index(IndexRequest(leaderIndex).id("1").source(source), RequestOptions.DEFAULT)
        assertThat(response.result).withFailMessage("Failed to create leader data").isEqualTo(Result.CREATED)

        follower.startReplication(StartReplicationRequest("source", leaderIndex, followerIndex), waitForRestore=true)

        assertBusy {
            val getResponse = follower.get(GetRequest(followerIndex, "1"), RequestOptions.DEFAULT)
            assertThat(getResponse.isExists).isTrue()
            assertThat(getResponse.sourceAsMap).isEqualTo(source)
        }
        follower.stopReplication(followerIndex)
    }

    fun `test that index operations are replayed to follower during replication`() {
        val followerClient = getClientForCluster(FOLL)
        val leaderClient = getClientForCluster(LEADER)
        createConnectionBetweenClusters(FOLL, LEADER)

        val createIndexResponse = leaderClient.indices().create(CreateIndexRequest(leaderIndexName), RequestOptions.DEFAULT)
        assertThat(createIndexResponse.isAcknowledged).isTrue()

        try {
            followerClient.startReplication(StartReplicationRequest("source", leaderIndexName, followerIndexName), waitForRestore=true)

            // Create document
            var source = mapOf("name" to randomAlphaOfLength(20), "age" to randomInt().toString())
            var response = leaderClient.index(IndexRequest(leaderIndexName).id("1").source(source), RequestOptions.DEFAULT)
            assertThat(response.result).withFailMessage("Failed to create leader data").isEqualTo(Result.CREATED)

            assertBusy({
                val getResponse = followerClient.get(GetRequest(followerIndexName, "1"), RequestOptions.DEFAULT)
                assertThat(getResponse.isExists).isTrue()
                assertThat(getResponse.sourceAsMap).isEqualTo(source)
            }, 60L, TimeUnit.SECONDS)

            // Update document
            source = mapOf("name" to randomAlphaOfLength(20), "age" to randomInt().toString())
            response = leaderClient.index(IndexRequest(leaderIndexName).id("1").source(source), RequestOptions.DEFAULT)
            assertThat(response.result).withFailMessage("Failed to update leader data").isEqualTo(Result.UPDATED)

            assertBusy({
                val getResponse = followerClient.get(GetRequest(followerIndexName, "1"), RequestOptions.DEFAULT)
                assertThat(getResponse.isExists).isTrue()
                assertThat(getResponse.sourceAsMap).isEqualTo(source)
            },60L, TimeUnit.SECONDS)

            // Delete document
            val deleteResponse = leaderClient.delete(DeleteRequest(leaderIndexName).id("1"), RequestOptions.DEFAULT)
            assertThat(deleteResponse.result).withFailMessage("Failed to delete leader data").isEqualTo(Result.DELETED)

            assertBusy({
                val getResponse = followerClient.get(GetRequest(followerIndexName, "1"), RequestOptions.DEFAULT)
                assertThat(getResponse.isExists).isFalse()
            }, 60L, TimeUnit.SECONDS)
        } finally {
            followerClient.stopReplication(followerIndexName)
        }
    }
}
