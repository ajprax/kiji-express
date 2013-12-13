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

package org.kiji.express.flow

import org.kiji.annotations.ApiAudience
import org.kiji.annotations.ApiStability

/** Specification of how to include the Kiji EntityId in a flow. */
@ApiAudience.Public
@ApiStability.Experimental
sealed trait EntityIdSpec {
  private[express] def fields: Iterable[String]
}

/** Module to provide EntityIdSpec implementations. */
@ApiAudience.Public
@ApiStability.Experimental
object EntityIdSpec {

  /** Do not include the EntityId in a Field. May not be used with a Kiji table output Source. */
  @ApiAudience.Public
  @ApiStability.Experimental
  case object NoEntityIdSpec extends EntityIdSpec {
    private[express] override def fields: Iterable[String] = Set()
  }

  /**
   * Include the EntityId in the given Field.
   *
   * @param field in which to store the EntityId.
   */
  @ApiAudience.Public
  @ApiStability.Experimental
  final case class EntityIdField(field: Symbol) extends EntityIdSpec {
    private[express] override def fields: Iterable[String] = Set(field.name)
  }

  /**
   * Include the components of the EntityId in the associated Fields. If used as a Kiji table output
   * Source, included components must conform to the rules of EntityId construction. Unspecified
   * components will default to null.
   *
   * @param components mapping from component index to Field in which to store that component.
   */
  @ApiAudience.Public
  @ApiStability.Experimental
  final case class EntityIdComponents(components: Map[Int, Symbol]) extends EntityIdSpec {
    private[express] override def fields: Iterable[String] = components.values.map { _.name }
  }

  /**
   * Companion object providing factory methods for constructing
   * [[org.kiji.express.flow.EntityIdSpec.EntityIdComponents]] instances.
   */
  @ApiAudience.Public
  @ApiStability.Experimental
  object EntityIdComponents {

    /**
     * Create a new [[org.kiji.express.flow.EntityIdSpec.EntityIdComponents]] from the given
     * component to Field mapping. If used as a Kiji table output Source, included components must
     * conform to the rules of EntityId construction. Unspecified components will default to null.
     *
     * @param components mapping from component name to Field in which to store that component.
     * @return a new EntityIdComponents
     */
    def apply(components: Iterable[(Int, Symbol)]): EntityIdComponents = apply(components.toMap)

    /**
     * Create a new [[org.kiji.express.flow.EntityIdSpec.EntityIdComponents]] from the given
     * component to Field mapping. If used as a Kiji table output Source, included components must
     * conform to the rules of EntityId construction. Unspecified components will default to null.
     *
     * @param components mapping from component name to Field in which to store that component.
     * @return a new EntityIdComponents
     */
    def apply(components: (Int, Symbol)*): EntityIdComponents = apply(components)
  }
}
