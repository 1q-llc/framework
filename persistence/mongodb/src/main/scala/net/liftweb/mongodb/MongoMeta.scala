/*
* Copyright 2010-2019 WorldWide Conferencing, LLC
*
* Licensed under the Apache License, Version 2.0 (the "License");
* you may not use this file except in compliance with the License.
* You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0
* Unless required by applicable law or agreed to in writing, software
* distributed under the License is distributed on an "AS IS" BASIS,
* WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
* See the License for the specific language governing permissions and
* limitations under the License.
*/

package net.liftweb
package mongodb

import net.liftweb.json.{DefaultFormats, Formats}
import net.liftweb.json.JsonAST.JObject
import net.liftweb.util.ConnectionIdentifier

import scala.collection.JavaConverters._

import com.mongodb.{BasicDBObject, DB, DBCollection, DBObject}
import com.mongodb.client.{FindIterable, MongoCollection, MongoDatabase}
import com.mongodb.client.model.{DeleteOptions, IndexOptions, UpdateOptions}
import com.mongodb.client.result.{DeleteResult, UpdateResult}

import org.bson.{BsonDocument, Document}
import org.bson.conversions.Bson
import org.bson.types.ObjectId

trait JsonFormats {
  // override this for custom Formats
  def formats: Formats = DefaultFormats.lossless

  implicit lazy val _formats: Formats = formats

  lazy val allFormats = DefaultFormats.lossless + new ObjectIdSerializer + new DateSerializer + new DateTimeSerializer + new PatternSerializer + new UUIDSerializer
}

/**
 * This is used by both MongoDocumentMeta and MongoMetaRecord
 */
trait MongoMeta[BaseDocument, TDocument] extends JsonFormats {

  def connectionIdentifier: ConnectionIdentifier

  // class name has a $ at the end.
  private lazy val _collectionName = getClass.getName.replaceAllLiterally("$", "")

  /**
   * Collection names should begin with letters or an underscore and may include
   * numbers; $ is reserved. Collections can be organized in namespaces; these
   * are named groups of collections defined using a dot notation. For example,
   * you could define collections blog.posts and blog.authors, both reside under
   * "blog". Note that this is simply an organizational mechanism for the user
   * -- the collection namespace is flat from the database's perspective.
   * From: http://www.mongodb.org/display/DOCS/Collections
   */
  def fixCollectionName = {
    val colName = MongoRules.collectionName.vend.apply(connectionIdentifier, _collectionName)

    if (colName.contains("$")) colName.replaceAllLiterally("$", "_d_")
    else colName
  }

  /**
   * The name of the database collection.  Override this method if you
   * want to change the collection to something other than the name of
   * the class with an 's' appended to the end.
   */
  def collectionName: String = fixCollectionName

  /**
   * Use the collection associated with this Meta.
   */
  def useCollection[T](f: MongoCollection[TDocument] => T): T

  @deprecated("Use useCollection instead", "3.3.1")
  def useColl[T](f: DBCollection => T): T

  /**
   * Use the db associated with this Meta.
   */
  def useDatabase[T](f: MongoDatabase => T): T

  @deprecated("Use useDatabase instead", "3.3.1")
  def useDb[T](f: DB => T): T

  /**
   * Count all documents
   */
  def count: Long = useCollection { coll => coll.countDocuments }

  /**
   * Count documents by Bson query
   */
  def count(qry: Bson):Long = useCollection { coll => coll.countDocuments(qry) }

  /**
   * Count documents by JObject query
   */
  def count(qry: JObject):Long = count(JsonParser.parse(qry))

  /**
   * Count distinct records on a given field.
   *
   * **Warning:** This retrieves all matching documents and puts them in memory.
   */
  def countDistinct(key: String, query: Bson): Long = {
    useCollection { coll => coll.distinct(key, query, classOf[Document]).iterator.asScala.toList.length }
  }

  /**
   * Delete a single document by a Bson query
   */
  def deleteOne(qry: Bson): DeleteResult = {
    useCollection(_.deleteOne(qry))
  }

  /**
   * Delete a single document by a Bson query with the given DeleteOptions
   */
  def deleteOne(qry: Bson, opts: DeleteOptions): DeleteResult = {
    useCollection(_.deleteOne(qry, opts))
  }

  /**
   * Delete a single document by a JObject query
   */
  def deleteOne(qry: JObject): DeleteResult =
    deleteOne(JsonParser.parse(qry))

  /**
   * Delete a single document by a JObject query with the given DeleteOptions
   */
  def deleteOne(qry: JObject, opts: DeleteOptions): DeleteResult =
    deleteOne(JsonParser.parse(qry), opts)

  /**
   * Delete a single document by a key-value pair query
   */
  def deleteOne(k: String, v: Any, opts: DeleteOptions = new DeleteOptions): DeleteResult = {
    deleteOne(new Document(k, v match {
      case s: String if (ObjectId.isValid(s)) => new ObjectId(s)
      case _ => v
    }), opts)
  }

  /**
   * Delete many documents by a Bson query
   */
  def deleteMany(qry: Bson): DeleteResult =
    useCollection(_.deleteMany(qry))

  /**
   * Delete many documents by a Bson query with the given DeleteOptions
   */
  def deleteMany(qry: Bson, opts: DeleteOptions): DeleteResult =
    useCollection(_.deleteMany(qry, opts))

  /**
   * Delete many documents by a JObject query
   */
  def deleteMany(qry: JObject): DeleteResult =
    deleteMany(JsonParser.parse(qry))

  /**
   * Delete many documents by a JObject query with the given DeleteOptions
   */
  def deleteMany(qry: JObject, opts: DeleteOptions): DeleteResult =
    deleteMany(JsonParser.parse(qry), opts)

  /**
   * Delete documents by a DBObject query
   */
  @deprecated("Use deleteOne or deleteMany instead", "3.3.1")
  def delete(qry: DBObject): Unit =
    useColl { coll => coll.remove(qry) }

  // delete a document
  @deprecated("Use deleteOne or deleteMany instead", "3.3.1")
  def delete(k: String, v: Any) {
    delete(new BasicDBObject(k, v match {
      case s: String if (ObjectId.isValid(s)) => new ObjectId(s)
      case _ => v
    }))
  }

  /**
   * Delete documents by a JObject query
   */
  @deprecated("Use deleteOne or deleteMany instead", "3.3.1")
  def delete(qry: JObject): Unit = delete(JObjectParser.parse(qry))

  /* drop this document collection */
  def drop: Unit =  useCollection { coll => coll.drop() }

  def createIndex(keys: Bson, opts: IndexOptions): String =
    useCollection(_.createIndex(keys, opts))

  def createIndex(keys: Bson): String =
    useCollection(_.createIndex(keys))

  def createIndex(keys: JObject, opts: IndexOptions): String =
    useCollection(_.createIndex(JsonParser.parse(keys), opts))

  def createIndex(keys: JObject): String =
    useCollection(_.createIndex(JsonParser.parse(keys)))

  def createIndex(keys: JObject, uniq: Boolean = false): String = {
    val options = (new IndexOptions).unique(uniq)
    useCollection(_.createIndex(JsonParser.parse(keys), options))
  }

  @deprecated("Use createIndex that takes IndexOptions as argument instead", "3.3.1")
  def createIndex(keys: JObject, opts: JObject): Unit =
    useColl { coll =>
      coll.createIndex(JObjectParser.parse(keys), JObjectParser.parse(opts))
    }

  /**
   * Update a single document with a Bson query
   */
  def updateOne(qry: Bson, update: Bson): UpdateResult =
    useCollection(_.updateOne(qry, update))

  /**
   * Update a single document with a Bson query with the given UpdateOptions
   */
  def updateOne(qry: Bson, update: Bson, opts: UpdateOptions): UpdateResult =
    useCollection(_.updateOne(qry, update, opts))

  /**
   * Update a single document with a JObject query
   */
  def updateOne(qry: JObject, update: JObject): UpdateResult =
    updateOne(JsonParser.parse(qry), JsonParser.parse(update))

  /**
   * Update a single document with a JObject query with the given UpdateOptions
   */
  def updateOne(qry: JObject, update: JObject, opts: UpdateOptions): UpdateResult =
    updateOne(JsonParser.parse(qry), JsonParser.parse(update))

  /**
   * Update many documents with a Bson query
   */
  def updateMany(qry: Bson, update: Bson): UpdateResult =
    useCollection(_.updateMany(qry, update))

  /**
   * Update many documents with a Bson query with the given UpdateOptions
   */
  def updateMany(qry: Bson, update: Bson, opts: UpdateOptions): UpdateResult =
    useCollection(_.updateMany(qry, update, opts))

  /**
   * Update many documents with a JObject query
   */
  def updateMany(qry: JObject, update: JObject): UpdateResult =
    updateMany(JsonParser.parse(qry), JsonParser.parse(update))

  /**
   * Update many documents with a JObject query with the given UpdateOptions
   */
  def updateMany(qry: JObject, update: JObject, opts: UpdateOptions): UpdateResult =
    updateMany(JsonParser.parse(qry), JsonParser.parse(update))

  @deprecated("Use updateOne or updateMany instead", "3.3.1")
  def update(qry: DBObject, newobj: DBObject, db: DB, opts: UpdateOption*) {
    val dboOpts = opts.toList
    db.getCollection(collectionName).update(
      qry,
      newobj,
      dboOpts.find(_ == Upsert).map(x => true).getOrElse(false),
      dboOpts.find(_ == Multi).map(x => true).getOrElse(false)
    )
  }

  @deprecated("Use updateOne or updateMany instead", "3.3.1")
  def update(qry: JObject, newobj: JObject, db: DB, opts: UpdateOption*) {
    update(
      JObjectParser.parse(qry),
      JObjectParser.parse(newobj),
      db,
      opts :_*
    )
  }

  @deprecated("Use updateOne or updateMany instead", "3.3.1")
  def update(qry: JObject, newobj: JObject, opts: UpdateOption*) {
    useDb { db => update(qry, newobj, db, opts :_*) }
  }
}

/**
 * For passing in options to the find function
 */
abstract sealed class FindOption {
  def value: Int
}
case class Limit(value: Int) extends FindOption
case class Skip(value: Int) extends FindOption

/*
* For passing in options to the update function
*/
@deprecated("Use com.mongodb.client.model.UpdateOptions instead", "3.3.1")
abstract sealed class UpdateOption
@deprecated("Use com.mongodb.client.model.UpdateOptions instead", "3.3.1")
case object Upsert extends UpdateOption
@deprecated("Use com.mongodb.client.model.UpdateOptions instead", "3.3.1")
case object Multi extends UpdateOption

