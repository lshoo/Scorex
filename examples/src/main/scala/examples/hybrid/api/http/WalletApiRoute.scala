package examples.hybrid.api.http

import javax.ws.rs.Path

import akka.actor.{ActorRef, ActorRefFactory}
import akka.http.scaladsl.server.Route
import examples.commons.{SimpleBoxTransaction, SimpleBoxTransactionMemPool}
import examples.curvepos.Value
import examples.hybrid.history.HybridHistory
import examples.hybrid.state.HBoxStoredState
import examples.hybrid.wallet.HWallet
import io.circe.parser._
import io.circe.syntax._
import io.swagger.annotations._
import scorex.core.LocalInterface.LocallyGeneratedTransaction
import scorex.core.api.http.{ApiException, ApiRouteWithFullView, SuccessApiResponse}
import scorex.core.settings.{RESTApiSettings, ScorexSettings}
import scorex.core.transaction.box.proposition.PublicKey25519Proposition
import scorex.core.transaction.state.PrivateKey25519
import scorex.crypto.encode.Base58
import scorex.crypto.signatures.PublicKey

import scala.concurrent.ExecutionContext.Implicits.global
import scala.util.{Failure, Success, Try}


case class WalletApiRoute(override val settings: RESTApiSettings, nodeViewHolderRef: ActorRef)
                         (implicit val context: ActorRefFactory)
  extends ApiRouteWithFullView[HybridHistory, HBoxStoredState, HWallet, SimpleBoxTransactionMemPool] {

  //TODO move to settings?
  val DefaultFee = 100

  override val route = pathPrefix("wallet") {
    balances ~ transfer
  }

  def transfer: Route = path("transfer") {
    entity(as[String]) { body =>
      withAuth {
        postJsonRoute {
          viewAsync().map { view =>
            parse(body) match {
              case Left(failure) => ApiException(failure.getCause)
              case Right(json) => Try {
                val wallet = view.vault
                val amount: Long = (json \\ "amount").head.asNumber.get.toLong.get
                val recipient: PublicKey25519Proposition = PublicKey25519Proposition(PublicKey @@ Base58.decode((json \\ "recipient").head.asString.get).get)
                val fee: Long = (json \\ "fee").head.asNumber.flatMap(_.toLong).getOrElse(DefaultFee)
                val tx = SimpleBoxTransaction.create(wallet, Seq((recipient, Value @@ amount)), fee).get
                nodeViewHolderRef ! LocallyGeneratedTransaction[PublicKey25519Proposition, SimpleBoxTransaction](tx)
                tx.json
              } match {
                case Success(resp) => SuccessApiResponse(resp)
                case Failure(e) => ApiException(e)
              }
            }
          }
        }
      }
    }
  }

  def balances: Route = path("balances") {
    getJsonRoute {
      viewAsync().map { view =>
        val wallet = view.vault
        val boxes = wallet.boxes()

        SuccessApiResponse(Map(
          "totalBalance" -> boxes.map(_.box.value.toLong).sum.toString.asJson,
          "publicKeys" -> wallet.publicKeys.map(pk => Base58.encode(pk.pubKeyBytes)).asJson,
          "boxes" -> boxes.map(_.box.json).asJson
        ).asJson)
      }
    }
  }

}