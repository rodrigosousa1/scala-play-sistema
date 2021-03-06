package dao

import javax.inject._
import models.{ Customer, Phone, CustomerDetails, Address }
import play.api.db.slick.DatabaseConfigProvider
import slick.driver.JdbcProfile
import scala.concurrent.Future
import scala.concurrent.ExecutionContext.Implicits.global

@Singleton
class CustomerDAOImpl @Inject() (dbConfigProvider: DatabaseConfigProvider) extends CustomerDAO with Tables {

  protected val dbConfig = dbConfigProvider.get[JdbcProfile]

  import dbConfig._
  import driver.api._

  def getById(id: Long): Future[Option[Customer]] = {
    val query = customersQuery.filter(_.id === id).result.headOption
    db.run(query)
  }

  def getAll(): Future[Seq[Customer]] = {
    db.run(customersQuery.result)
  }

  def save(customer: Customer): Future[Long] = {
    val query = customersAutoInc += customer
    db.run(query)
  }

  def delete(id: Long): Future[Int] = {
    val query = customersQuery.filter(_.id === id).delete
    db.run(query)
  }

  def update(id: Long, customer: Customer): Future[Int] = {
    val query = customersQuery.filter(_.id === id).update(customer)
    db.run(query)
  }

  def getDetailsById(id: Long): Future[Option[CustomerDetails]] = {
    val query = customersQuery.joinLeft(phonesQuery).on(_.id === _.customerId).joinLeft(addressesQuery).on(_._1.id === _.customerId).
      filter { case ((customer, phone), address) => customer.id === id }.result.map {
        _.groupBy(record => (record._1._1, record._2)).map {
          case ((c, a), p) => CustomerDetails(c.id, c.name, c.cnpj, c.registration, p.flatMap(_._1._2), a.getOrElse(Address.default))
        }.headOption
      }
    db.run(query)
  }

  def getAllDetails(): Future[Seq[CustomerDetails]] = {
    val query = customersQuery.joinLeft(phonesQuery).on(_.id === _.customerId).joinLeft(addressesQuery).on(_._1.id === _.customerId).result.map {
      _.groupBy(record => (record._1._1, record._2)).map {
        case ((c, a), p) => CustomerDetails(c.id, c.name, c.cnpj, c.registration, p.flatMap(_._1._2), a.getOrElse(Address.default))
      }.to[Seq]
    }

    db.run(query)
  }

  def saveDetails(customerDetails: CustomerDetails): Future[Int] = {
    val customer = Customer(customerDetails.name, customerDetails.cnpj, customerDetails.registration, customerDetails.id)
    val phones = customerDetails.phones

    val insertCustomer = customersAutoInc += customer

    def insertDetails(id: Long) = {
      val phonesList = phones.map(p => p.copy(customerId = id)).to[List]
      val address = customerDetails.address.copy(customerId = id)

      val insertPhones = phonesQuery ++= phonesList
      val insertAddress = addressesQuery += address

      insertPhones andThen insertAddress

    }

    val query = insertCustomer.flatMap { id => insertDetails(id) }
    db.run(query.transactionally)

  }

  def updateDetails(id: Long, customerDetails: CustomerDetails): Future[Int] = {
    val customer = Customer(customerDetails.name, customerDetails.cnpj, customerDetails.registration, customerDetails.id)
    val phones = customerDetails.phones
    val address = customerDetails.address

    val updateCustomer = customersQuery.filter(_.id === id).update(customer)
    val updatePhones = DBIO.sequence(phones.map(phone => phonesQuery.insertOrUpdate(phone)))
    val updateAddress = addressesQuery.insertOrUpdate(address)

    val query = updateCustomer andThen updatePhones andThen updateAddress

    db.run(query.transactionally)

  }

}