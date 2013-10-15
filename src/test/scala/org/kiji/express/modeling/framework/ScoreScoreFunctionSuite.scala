/**
 * (c) Copyright 2013 WibiData, Inc.
 *
 * See the NOTICE file distributed with this work for additional
 * information regarding copyright ownership.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.kiji.express.modeling.framework

import scala.collection.JavaConverters.mapAsJavaMapConverter

import org.apache.hadoop.fs.Path

import org.kiji.express.KijiSuite
import org.kiji.express.modeling.config.ExpressColumnRequest
import org.kiji.express.modeling.config.ExpressDataRequest
import org.kiji.express.modeling.config.FieldBinding
import org.kiji.express.modeling.config.KeyValueStoreSpec
import org.kiji.express.modeling.config.KijiInputSpec
import org.kiji.express.modeling.config.KijiSingleColumnOutputSpec
import org.kiji.express.modeling.config.ModelDefinition
import org.kiji.express.modeling.config.ModelEnvironment
import org.kiji.express.modeling.config.ScoreEnvironment
import org.kiji.express.modeling.impl.KeyValueStoreImplSuite
import org.kiji.express.util.Resources.doAndClose
import org.kiji.express.util.Resources.doAndRelease
import org.kiji.schema.EntityId
import org.kiji.schema.Kiji
import org.kiji.schema.KijiColumnName
import org.kiji.schema.KijiDataRequest
import org.kiji.schema.KijiRowData
import org.kiji.schema.KijiTable
import org.kiji.schema.KijiTableReader
import org.kiji.schema.KijiURI
import org.kiji.schema.layout.KijiTableLayout
import org.kiji.schema.layout.KijiTableLayouts
import org.kiji.schema.util.InstanceBuilder
import org.kiji.scoring.FreshKijiTableReader
import org.kiji.scoring.KijiFreshnessManager

class ScoreScoreFunctionSuite extends KijiSuite {
  test("An extract-score ScoreFunction can be attached and run.") {
    val testLayout: KijiTableLayout = layout(KijiTableLayouts.SIMPLE_TWO_COLUMNS)

    val kiji: Kiji = new InstanceBuilder("default")
        .withTable(testLayout.getName, testLayout)
            .withRow("row1")
                .withFamily("family")
                    .withQualifier("column1").withValue("foo")
            .withRow("row2")
                .withFamily("family")
                    .withQualifier("column1").withValue("bar")
        .build()
    try {
      doAndRelease(kiji.openTable(testLayout.getName)) { table: KijiTable =>
        val uri: KijiURI = table.getURI

        val eid: EntityId = table.getEntityId("row1")
        val modelRequest: ExpressDataRequest = new ExpressDataRequest(0, Long.MaxValue,
            new ExpressColumnRequest("family:column1", 1, None) :: Nil)
        val sideDataPath: Path = KeyValueStoreImplSuite.generateAvroKVRecordKeyValueStore()

        doAndClose(KijiFreshnessManager.create(kiji)) { manager: KijiFreshnessManager =>
          val policyClass: String = "org.kiji.scoring.lib.AlwaysFreshen"
          val scoreFunctionClass: String = "org.kiji.express.modeling.framework.ScoreScoreFunction"
          val modelDef: ModelDefinition = ModelDefinition(
              name = "test-model-definition",
              version = "1.0",
              scoreExtractorClass = Some(classOf[ScoreProducerSuite.DoublingExtractor]),
              scorerClass = Some(classOf[ScoreProducerSuite.UpperCaseScorer]))
          val modelEnv: ModelEnvironment = ModelEnvironment(
              name = "test-model-environment",
              version = "1.0",
              prepareEnvironment = None,
              trainEnvironment = None,
              scoreEnvironment = Some(ScoreEnvironment(
                KijiInputSpec(
                  uri.toString,
                  dataRequest = modelRequest,
                  fieldBindings = Seq(
                    FieldBinding(tupleFieldName = "field", storeFieldName = "family:column1"))),
                KijiSingleColumnOutputSpec(uri.toString, "family:column2"),
                keyValueStoreSpecs = Seq(
                  KeyValueStoreSpec(
                    storeType = "AVRO_KV",
                    name = "side_data",
                    properties = Map(
                      "path" -> sideDataPath.toString,
                      // The Distributed Cache is not supported when using LocalJobRunner in
                      // Hadoop <= 0.21.0.
                      // See https://issues.apache.org/jira/browse/MAPREDUCE-476 for more
                      // information.
                      "use_dcache" -> "false"))))))

          val parameters: Map[String, String] = Map(
              ScoreScoreFunction.modelDefinitionParameterKey -> modelDef.toJson,
              ScoreScoreFunction.modelEnvironmentParameterKey -> modelEnv.toJson)

          val outputColumn: KijiColumnName =
              new KijiColumnName(modelEnv.scoreEnvironment.get.outputSpec.outputColumn)

          manager.registerFreshener(
              uri.getTable,
              outputColumn,
              policyClass,
              scoreFunctionClass,
              parameters.asJava,
              false,
              false,
              false)

          doAndClose(FreshKijiTableReader.Builder.create()
              .withTable(table)
              .withTimeout(10000)
              .build()) {
            freshReader: FreshKijiTableReader => {
              doAndClose(table.openTableReader()) { reader: KijiTableReader =>
                val request: KijiDataRequest = KijiDataRequest.create("family", "column2")
                val data: KijiRowData = reader.get(eid, request)
                assert(null == data.getMostRecentValue("family", "column2"))
                val freshData: KijiRowData = freshReader.get(eid, request)
                assert("FOOFOOONE" === freshData.getMostRecentValue("family", "column2").toString)
              }
            }
          }

        }
      }
    } finally {
      kiji.release()
    }
  }
}
