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

import ScoreScoreFunction.modelDefinitionParameterKey
import ScoreScoreFunction.modelEnvironmentParameterKey

import java.util.{Map => JMap}
import scala.collection.JavaConverters.mapAsJavaMapConverter

import org.apache.hadoop.conf.Configuration

import org.kiji.annotations.ApiAudience
import org.kiji.annotations.ApiStability
import org.kiji.express.EntityId
import org.kiji.express.KijiSlice
import org.kiji.express.flow.framework.KijiScheme
import org.kiji.express.modeling.ExtractFn
import org.kiji.express.modeling.Extractor
import org.kiji.express.modeling.ScoreFn
import org.kiji.express.modeling.Scorer
import org.kiji.express.modeling.config.KeyValueStoreSpec
import org.kiji.express.modeling.config.KijiInputSpec
import org.kiji.express.modeling.config.ModelDefinition
import org.kiji.express.modeling.config.ModelEnvironment
import org.kiji.express.modeling.impl.ModelJobUtils
import org.kiji.express.modeling.impl.ModelJobUtils.PhaseType.SCORE
import org.kiji.express.util.GenericRowDataConverter
import org.kiji.express.util.Tuples
import org.kiji.mapreduce.kvstore.{KeyValueStore => JKeyValueStore}
import org.kiji.scoring.FreshenerContext
import org.kiji.scoring.FreshenerGetStoresContext
import org.kiji.scoring.FreshenerSetupContext
import org.kiji.scoring.ScoreFunction
import org.kiji.schema.KijiColumnName
import org.kiji.schema.KijiDataRequest
import org.kiji.schema.KijiRowData
import org.kiji.schema.KijiURI

/**
 * A KijiScoring ScoreFunction implementation for running
 * [[org.kiji.express.modeling.config.ModelDefinition]]s as Fresheners.
 *
 * This ScoreFunction executes the score phase of a model. The model that this ScoreFunction will
 * run is loaded from the JSON configuration strings stored in parameters keys:
 * <ul>
 *   <li>
 * </ul>
 */
@ApiAudience.Framework
@ApiStability.Experimental
final class ScoreScoreFunction[T] extends ScoreFunction[T] {
  /** The model definition. This variable must be initialized. */
  private[this] var _modelDefinition: Option[ModelDefinition] = None
  private[this] def modelDefinition: ModelDefinition = {
    _modelDefinition.getOrElse {
      throw new IllegalStateException("ScoreScoreFunction is missing its model definition")
    }
  }

  /** Environment required to run phases of a model. This variable must be initialized. */
  private[this] var _modelEnvironment: Option[ModelEnvironment] = None
  private[this] def modelEnvironment: ModelEnvironment = {
    _modelEnvironment.getOrElse {
      throw new IllegalStateException(
        "ScoreProducer is missing its run profile. Did setConf get called?")
    }
  }

  /** Extractor to use for this model definition. This variable must be initialized. */
  private[this] var _extractor: Option[Extractor] = None
  private[this] def extractor: Option[Extractor] = {
    _extractor
  }

  /** Scorer to use for this model definition. This variable must be initialized. */
  private[this] var _scorer: Option[Scorer] = None
  private[this] def scorer: Scorer = {
    _scorer.getOrElse {
      throw new IllegalStateException(
        "ScoreProducer is missing its scorer. Did setConf get called?")
    }
  }

  /** A converter that configured row data to decode data generically. */
  private[this] var _rowConverter: Option[GenericRowDataConverter] = None
  private[this] def rowConverter: GenericRowDataConverter = {
    _rowConverter.getOrElse {
      throw new IllegalStateException("ExtractScoreProducer is missing its row data converter. "
          + "Was setup() called?")
    }
  }

  override def getRequiredStores(
      context: FreshenerGetStoresContext
  ): JMap[String, JKeyValueStore[_, _]] = {
    // Because this is the first method called in the ScoreFunction lifecycle, set up the basic
    // state here.
    _modelDefinition =
        Some(ModelDefinition.fromJson(context.getParameter(modelDefinitionParameterKey)))
    _modelEnvironment =
        Some(ModelEnvironment.fromJson(context.getParameter(modelEnvironmentParameterKey)))
    _extractor = modelDefinition.scoreExtractorClass.map { _ .newInstance() }
    _scorer = Some(modelDefinition.scorerClass.get.newInstance())

    val scoreStoreDefs: Seq[KeyValueStoreSpec] =
        modelEnvironment.scoreEnvironment.get.keyValueStoreSpecs
    val scoreStores: Map[String, JKeyValueStore[_, _]] =
        ModelJobUtils.openJKvstores(scoreStoreDefs, new Configuration())
    scoreStores.asJava
  }

  override def setup(context: FreshenerSetupContext): Unit = {
    // Setup the score phase's key value stores.
    val scoreStoreDefs: Seq[KeyValueStoreSpec] =
        modelEnvironment.scoreEnvironment.get.keyValueStoreSpecs
    scorer.keyValueStores = ModelJobUtils.wrapKvstoreReaders(scoreStoreDefs, context)
    extractor.map { e => e.keyValueStores = scorer.keyValueStores }

    // Setup the row converter.
    val uriString =
      modelEnvironment.scoreEnvironment.get.inputSpec.asInstanceOf[KijiInputSpec].tableUri
    val uri = KijiURI.newBuilder(uriString).build()
    _rowConverter = Some(new GenericRowDataConverter(uri, new Configuration))
  }

  override def cleanup(context: FreshenerSetupContext): Unit = {
    rowConverter.close()
  }

  override def getDataRequest(context: FreshenerContext): KijiDataRequest = {
    ModelJobUtils.getDataRequest(modelEnvironment, SCORE).get
  }

  override def score(input: KijiRowData, context: FreshenerContext): T = {
    val ScoreFn(scoreFields, score) = scorer.scoreFn

    // Setup fields.
    val fieldMapping: Map[String, KijiColumnName] = modelEnvironment
        .scoreEnvironment.get
        .inputSpec.asInstanceOf[KijiInputSpec]
        .fieldBindings
        .map { binding =>
          (binding.tupleFieldName, new KijiColumnName(binding.storeFieldName))
        }
        .toMap

    // Configure the row data input to decode its data generically
    val row = rowConverter(input)

    // Prepare input to the extract phase.
    def getSlices(inputFields: Seq[String]): Seq[Any] = inputFields
        .map { (field: String) =>
          if (field == KijiScheme.entityIdField) {
            EntityId.fromJavaEntityId(row.getEntityId)
          } else {
            val columnName: KijiColumnName = fieldMapping(field.toString)

            // Build a slice from each column within the row.
            if (columnName.isFullyQualified) {
              KijiSlice[Any](row, columnName.getFamily, columnName.getQualifier)
            } else {
              KijiSlice[Any](row, columnName.getFamily)
            }
          }
        }

    val extractFnOption: Option[ExtractFn[_, _]] = extractor.map { _.extractFn }
    val scoreInput = extractFnOption match {
      // If there is an extractor, use its extractFn to set up the correct input and output fields
      case Some(ExtractFn(extractFields, extract)) => {
        val extractInputFields: Seq[String] = {
          // If the field specified is the wildcard field, use all columns referenced in this model
          // environment's field bindings.
          if (extractFields._1.isAll) {
            fieldMapping.keys.toSeq
          } else {
            Tuples.fieldsToSeq(extractFields._1)
          }
        }
        val extractOutputFields: Seq[String] = {
          // If the field specified in the results field, use all input fields from the extract
          // phase.
          if (extractFields._2.isResults) {
            extractInputFields
          } else {
            Tuples.fieldsToSeq(extractFields._2)
          }
        }

        val scoreInputFields: Seq[String] = {
          // If the field specified is the wildcard field, use all fields output by the extract
          // phase.
          if (scoreFields.isAll) {
            extractOutputFields
          } else {
            Tuples.fieldsToSeq(scoreFields)
          }
        }

        // Prepare input to the extract phase.
        val slices = getSlices(extractInputFields)

        // Get output from the extract phase.
        val featureVector: Product = Tuples.fnResultToTuple(
          extract(Tuples.tupleToFnArg(Tuples.seqToTuple(slices))))
        val featureMapping: Map[String, Any] = extractOutputFields
            .zip(featureVector.productIterator.toIterable)
            .toMap

        // Get a score from the score phase.
        val scoreInput: Seq[Any] = scoreInputFields.map { field => featureMapping(field) }

        scoreInput
      }
    }

    // Return the calculated score.
    score(Tuples.tupleToFnArg(Tuples.seqToTuple(scoreInput))).asInstanceOf[T]
  }
}

object ScoreScoreFunction {
  val modelDefinitionParameterKey: String =
      "org.kiji.express.modeling.framework.ScoreScoreFunction.definition"
  val modelEnvironmentParameterKey: String =
      "org.kiji.express.modeling.framework.ScoreScoreFunction.environment"
}
