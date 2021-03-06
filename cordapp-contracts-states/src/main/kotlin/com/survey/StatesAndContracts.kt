package com.survey

import com.sun.crypto.provider.AESKeyGenerator
import net.corda.core.contracts.*
import net.corda.core.crypto.SecureHash
import net.corda.core.identity.AbstractParty
import net.corda.core.identity.Party
import net.corda.core.transactions.LedgerTransaction
import net.corda.finance.contracts.asset.Cash



// *****************
// * Contract Code *
// *****************
// This is used to identify our contract when building a transaction

class SurveyContract : Contract {

    companion object {
        @JvmStatic
        val SURVEY_CONTRACT_ID = "com.survey.SurveyContract"
    }
    // A transaction is considered valid if the verify() function of the contract of each of the transaction's input
    // and output states does not throw an exception.
    override fun verify(tx: LedgerTransaction) {
        val command = tx.commands.requireSingleCommand<Commands>()
        when(command.value){
            is Commands.IssueRequest -> {

            }
            is Commands.Issue -> {
                requireThat {
                    "Only one output state should be created." using (tx.outputs.size == 1)
                    "No inputs should be consumed when issuing a survey." using (tx.inputs.isEmpty())
                    val out = tx.outputsOfType<SurveyState>().single()
                    "The issuer and the owner must be the same entity." using (out.issuer == out.owner)
                    "Issuer must be the signer." using (command.signers.contains(out.issuer.owningKey))
                    // Survey-specific constraints.
                    "The survey's value must be non-negative." using (out.initialPrice > 0)
                    "The initial price is equal to the resale price" using (out.initialPrice != out.resalePrice)
                }
            }
            is Commands.Trade -> {
                requireThat {
                    "There should be two input states" using (tx.inputStates.size == 2)
                    "There should be two output states" using (tx.outputStates.size == 2)
                    val inputSurvey = tx.inputsOfType<SurveyState>().single()
                    val outputSurvey = tx.outputsOfType<SurveyState>().single()
                    val inputCash = tx.inputsOfType<Cash.State>().single()
                    val outputCash = tx.outputsOfType<Cash.State>().single()

                    "Resale price should be positive" using (outputSurvey.resalePrice > 0)
                    "Resale price should be less than initial price" using (inputSurvey.resalePrice > inputSurvey.initialPrice)

                    "Input cash should be more than the purchasing price" using (inputCash.amount.quantity > inputSurvey.initialPrice)
                    "Output cash should be equal to resell price" using (outputCash.amount.quantity.toInt() == inputSurvey.resalePrice)

                    "The person who owns the cash initially, now owns the survey." using (inputCash.owner == outputSurvey.owner)
                    "The person who owns survey initially, now owns the cash." using (inputSurvey.owner == outputCash.owner)
                    "Cannot sell survey to yourself" using (inputSurvey.owner == outputSurvey.owner)

                    "All of the survey participants must be signers." using (command.signers.containsAll(inputSurvey.participants.map { it.owningKey }))
                    "The cash owner must be signer." using (command.signers.containsAll(inputCash.participants.map { it.owningKey }))
                }
            }
        }
    }

    // Used to indicate the transaction's intent.
    interface Commands : CommandData {
        class Issue : Commands
        class Trade : Commands
        class IssueRequest : Commands
        class OracleCommand(val linearId: UniqueIdentifier) : Commands
    }
}

// *********
// * State *
// *********
data class SurveyState(val issuer: Party,
                       val owner: Party,
                       val propertyAddress: String,
                       val landTitleId: String,
                       val surveyDate: String,
                       val initialPrice: Int,
                       val resalePrice : Int,
                       override val linearId: UniqueIdentifier) : LinearState {
    override val participants: List<AbstractParty> get() = listOf(issuer, owner)
}

// *********
// * SurveyIssuanceState
// * The facts shared by the surveyor to
// * the buyer once the survey is complete
// *********
data class SurveyKeyState(val encodedSurveyHash: String,
                          val encodedSurveyKey : String,
                          override val linearId: UniqueIdentifier) : LinearState {
    override val participants: List<AbstractParty> get() = listOf()
}

// *********
// * SurveyRequestState
// * For potential to make a request for a survey
// * at a particular address for price.
// *********
data class SurveyRequestState(val requester :Party,
                              val surveyor : Party,
                              val propertyAddress: String,
                              val landTitleId: String,
                              val surveyPrice : Int,
                              override val linearId : UniqueIdentifier) : LinearState {
    override val participants: List<AbstractParty>
        get() = listOf(requester)
}