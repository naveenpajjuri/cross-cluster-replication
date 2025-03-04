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

package org.opensearch.replication.action.status

import org.opensearch.action.ActionRequestValidationException
import org.opensearch.action.support.IndicesOptions
import org.opensearch.action.support.broadcast.BroadcastRequest
import org.opensearch.common.io.stream.StreamInput
import org.opensearch.common.io.stream.StreamOutput
import org.opensearch.common.xcontent.ToXContent
import org.opensearch.common.xcontent.ToXContentObject
import org.opensearch.common.xcontent.XContentBuilder

class ShardInfoRequest : BroadcastRequest<ShardInfoRequest> , ToXContentObject {

    var indexName: String
    var verbose: Boolean = false

    constructor(indexName: String) {
        this.indexName = indexName
    }

    constructor(indexName: String,verbose: Boolean) {
        this.indexName = indexName
        this.verbose = verbose
    }

    constructor(inp: StreamInput): super(inp) {
        indexName = inp.readString()
    }

    override fun validate(): ActionRequestValidationException? {
        return null
    }

    override fun indices(): Array<String> {
        return arrayOf(indexName)
    }

    override fun indicesOptions(): IndicesOptions {
        return IndicesOptions.strictSingleIndexNoExpandForbidClosed()
    }

    override fun toXContent(builder: XContentBuilder, params: ToXContent.Params): XContentBuilder {
        builder.startObject()
        builder.field("indexName", indexName)
        builder.endObject()
        return builder
    }

    override fun writeTo(out: StreamOutput) {
        super.writeTo(out)
        out.writeString(indexName)
    }

}
