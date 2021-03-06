package com.survey.api

import com.survey.SurveyState
import com.survey.flows.IssueFlow.SurveyIssuanceFlow
import com.survey.flows.IssueFlow.SurveyRequestFlow
import com.template.flow.SelfIssueCashFlow
import net.corda.finance.flows.*
import net.corda.core.contracts.Amount
import net.corda.core.crypto.SecureHash
import java.util.Currency
import net.corda.core.identity.CordaX500Name
import net.corda.core.identity.Party
import net.corda.core.messaging.CordaRPCOps
import net.corda.core.messaging.startFlow
import net.corda.core.messaging.vaultQueryBy
import net.corda.core.node.services.IdentityService
import net.corda.core.utilities.OpaqueBytes
import net.corda.core.utilities.getOrThrow
import javax.ws.rs.*
import javax.ws.rs.core.MediaType
import javax.ws.rs.core.Response
import net.corda.core.utilities.loggerFor
import net.corda.finance.contracts.asset.Cash
import org.slf4j.Logger
import javax.ws.rs.core.Response.Status.BAD_REQUEST
import javax.ws.rs.core.Response.Status.CREATED


// *****************
// * API Endpoints *
// *****************
@Path("survey")
class SurveyAPI(val rpcOps: CordaRPCOps) {

    private val myLegalName : CordaX500Name = rpcOps.nodeInfo().legalIdentities.first().name
    val SERVICE_NAMES = listOf("Notary", "Network Map Service")
    companion object {
        private val logger: Logger = loggerFor<SurveyAPI>()
    }

    /**
     * Test Endpoint accessible at /api/survey/test
     */
    @GET
    @Path("test")
    @Produces(MediaType.APPLICATION_JSON)
    fun templateGetEndpoint(): Response {
        return Response.ok("Survey GET endpoint.").build()
    }

    /**
     * Returns the node's name.
     */
    @GET
    @Path("me")
    @Produces(MediaType.APPLICATION_JSON)
    fun whoami() = mapOf("me" to myLegalName)

    /**
     * Returns all parties registered with the [NetworkMapService]. These names can be used to look up identities
     * using the [IdentityService].
     */
    @GET
    @Path("peers")
    @Produces(MediaType.APPLICATION_JSON)
    fun getPeers(): Map<String, List<CordaX500Name>> {
        val nodeInfo = rpcOps.networkMapSnapshot()
        return mapOf("peers" to nodeInfo
                .map { it.legalIdentities.first().name }
                //filter out myself, notary and eventual network map started by driver
                .filter { it.organisation !in (SERVICE_NAMES + myLegalName.organisation) })
    }

    /*
    * Displays all Survey states that exist in the node's vault.
    */
    @GET
    @Path("surveys")
    @Produces(MediaType.APPLICATION_JSON)
    fun getSurveys() = rpcOps.vaultQueryBy<SurveyState>().states

    /*
    * Upload Attachment Endpoint
    */
    @PUT
    @Path("upload/attachment")
    @Produces(MediaType.APPLICATION_JSON)
    fun uploadAttachment() {
        System.out.println("--------uploading attachment-------")
    }

    /**
     * Endpoint to for a potential buyer to request a Survey from a surveyor
     */
    @PUT
    @Path("survey-request")
    fun surveyRequest(@QueryParam("surveyor") surveyor: String, //e.g O=Surveyor,L=New York,C=US
                    @QueryParam("propertyAddress") propertyAddress : String,
                    @QueryParam("landTitleId") landTitleId : String,
                    @QueryParam("surveyPrice") surveyPrice : Int) : Response{

        if(surveyPrice < 0 ){
            return Response.status(Response.Status.BAD_REQUEST).entity("Survey price cannot be less then zero.\n").build()
        }
        val sx500Name = CordaX500Name.parse(surveyor)
        val surveyorParty = rpcOps.wellKnownPartyFromX500Name(sx500Name) ?: throw Exception("Party not recognised.")
        return try {
            val signedTx = rpcOps.startTrackedFlowDynamic(SurveyRequestFlow::class.java, surveyorParty, propertyAddress, landTitleId, surveyPrice).returnValue.getOrThrow()
            Response.status(Response.Status.CREATED).entity("SurveyRequestFlow success : Transaction id "+signedTx.hashCode()+" committed to ledger.\n").build()

        } catch (ex: Throwable) {
            logger.error(ex.message, ex)
            Response.status(Response.Status.BAD_REQUEST).entity(ex.message!!).build()
        }
    }


    /**
     * Endpoint for Surveyor to issue a survey
     */
    @PUT
    @Path("issue-survey")
    fun issueSurvey(@QueryParam("purchaser") purchaser : String,
                    @QueryParam("propertyAddress") propertyAddress : String,
                    @QueryParam("landTitleId") landTitleId : String,
                    @QueryParam("surveyDate") surveyDate: String,
                    @QueryParam("price") price : Int,
                    @QueryParam("encodedSurveyHash") encodedSurveyHash: String,
                    @QueryParam("encodedSurveyKey") encodedSurveyKey: String) : Response{

        if(price < 0){
            return Response.status(Response.Status.BAD_REQUEST).entity("Price cannot be less then zero.\n").build()
        }

        val purchaserx500Name = CordaX500Name.parse(purchaser)
        val purchaserParty = rpcOps.wellKnownPartyFromX500Name(purchaserx500Name) ?: throw Exception("Party not recognised.")

        return try {
            val signedTx = rpcOps.startTrackedFlowDynamic(SurveyIssuanceFlow::class.java, purchaserParty, surveyDate, price, propertyAddress, landTitleId, encodedSurveyHash, encodedSurveyKey).returnValue.getOrThrow()
            Response.status(Response.Status.CREATED).entity("Transaction id "+signedTx.hashCode()+" committed to ledger.\n").build()

        } catch (ex: Throwable) {
            logger.error(ex.message, ex)
            Response.status(Response.Status.BAD_REQUEST).entity(ex.message!!).build()
        }
    }

    @PUT
    @Path("self-issue-cash")
    fun selfIssueCash(@QueryParam(value = "amount") amount: Int,
                      @QueryParam(value = "currency") currency: String): Response {

        // 1. Prepare issue request.
        val issueAmount = Amount(amount.toLong() * 100, Currency.getInstance(currency))
        val notary = rpcOps.notaryIdentities().firstOrNull() ?: throw IllegalStateException("Could not find a notary.")
        val issueRef = OpaqueBytes.of(0)
        val issueRequest = CashIssueFlow.IssueRequest(issueAmount, issueRef, notary)

        // 2. Start flow and wait for response.
        return try {
            val flowHandle = rpcOps.startFlowDynamic(CashIssueFlow::class.java, issueRequest)
            Response.status(Response.Status.CREATED).entity("Cash Issued Successfully "+flowHandle.hashCode()+".\n").build()

        } catch (e: Exception) {
            Response.status(Response.Status.BAD_REQUEST).entity(e.message!!).build()

        }
    }
}


