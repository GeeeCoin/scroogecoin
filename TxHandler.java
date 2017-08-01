import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;

public class TxHandler {

    //declare the class variable to store the pool.
    private UTXOPool pool;

    /**
     * Creates a public ledger whose current UTXOPool (collection of unspent transaction outputs) is
     * {@code utxoPool}. This should make a copy of utxoPool by using the UTXOPool(UTXOPool uPool)
     * constructor.
     */
    public TxHandler(UTXOPool utxoPool) {
        //create a copy of the utxoPool that's passed in and store it in the class-level
        //utxoPool variable defined above. The
        pool = new UTXOPool(utxoPool);
    }

    /**
     * @return true if:
     * (1) all outputs claimed by {@code tx} are in the current UTXO pool, 
     * (2) the signatures on each input of {@code tx} are valid, 
     * (3) no UTXO is claimed multiple times by {@code tx},
     * (4) all of {@code tx}s output values are non-negative, and
     * (5) the sum of {@code tx}s input values is greater than or equal to the sum of its output
     *     values; and false otherwise.
     */
    public boolean isValidTx(Transaction tx) {
        if (tx == null)
            return false;

        //clone the tx passed in to prevent tampering of the data. Not sure if we really
        //need to be this detailed about it for this class, but trying to cover all basis here.
        Transaction txClone = new Transaction(tx);

        //(5) store the sum of the input values for comarison later against the output values.
        double inputValueSum = 0;
        double outputValueSum = 0;

        //(3) this is to keep track of the UTXOs that were already consumed in the current tx. Without this,
        //there could be a case that multiple inputs of the same tx point to the same utxo to consume. So
        //we need to track the ones we consumed, but without actually removing it from the pool just yet in case the tx
        //is not valid due to other rules that failed.
        Set<UTXO> consumedUTXO = new HashSet<UTXO>();

        for (int i = 0; i < txClone.numInputs(); i++) {
            //get the input at the current index.
            Transaction.Input input = txClone.getInput(i);
            //build a utxo object based on the data from the input we just got.
            UTXO utxo = new UTXO(input.prevTxHash, input.outputIndex);

            //(1) all outputs claimed by transaction are in the current UTXO pool
            if (!pool.contains(utxo)) {
                //the utxo was not in the pool. The transaction is therefore invalid.
                return false;
            }
            
            //(3) no UTXO is claimed multiple times by transaction.
            if (consumedUTXO.contains(utxo))
            {
                //the utxo was already claimed. This is double spending.
                return false;
            }

            //get the output for the utxo in the pool that matches the input.
            Transaction.Output utxoOutput = pool.getTxOutput(utxo);
            //always good to check for nulls.
            if (utxoOutput == null)
                return false;

            //(2) the signatures on each input of transaction are valid.
            boolean isSignatureValid = Crypto.verifySignature(utxoOutput.address, 
                txClone.getRawDataToSign(i), input.signature);

            if (!isSignatureValid) {
                //the signature of the input was invalid. The transaction is therefore invalid.
                return false;
            }
            
            //(5) keep track of the sum of the input values
            inputValueSum += utxoOutput.value;

            //(3) store the utxo to check against the other inputs to make sure they're not used more than once.
            consumedUTXO.add(utxo);
        }

        //now we loop through the outputs to check validity
        for (int i = 0; i < txClone.numOutputs(); i++)
        {
            Transaction.Output output = txClone.getOutput(i);
            //(4) all of tx output values are non-negative            
            if (output.value < 0)
                return false;

            //(5) calculate the sum of the tx outputs.
            outputValueSum += output.value;
        }

        //(5) the sum of txs input values is greater than or equal to the sum of its output values
        if (inputValueSum < outputValueSum)
            return false;

        return true;
    }

    /**
     * Handles each epoch by receiving an unordered array of proposed transactions, checking each
     * transaction for correctness, returning a mutually valid array of accepted transactions, and
     * updating the current UTXO pool as appropriate.
     */
    public Transaction[] handleTxs(Transaction[] possibleTxs) {
        ArrayList<Transaction> pendingTxsList = toArrayList(possibleTxs);
        ArrayList<Transaction> validTxsList = new ArrayList<Transaction>();

        //keep track if this iteration contains any valid transactions.  
        //If it does we need to reprocess the ones that failed until there are no more valid
        //transactions found. We do this because of the requirement that a tx in the batch
        //can actually reference the output of another tx in the same batch. We need to know when to stop processing.
        boolean validTxExists = false;
        do
        {
            //reset after each iteration to see if we find a valid tx this time around.
            validTxExists = false;

            //loop backwards to properly be able to remove items from the list.
            //this is a pattern for removing items from a list without affecting the loop.
            for(int i = pendingTxsList.size() - 1; i >= 0; i--)
            {
                Transaction tx = pendingTxsList.get(i);
                //this approach is not optimized but the logic works. We're potentially
                //checking if the same tx is valid multiple times (minus the utxo check).
                if (isValidTx(tx))
                {
                    //track the valid tx
                    validTxsList.add(tx);
                    //remove it from the pending tx list
                    pendingTxsList.remove(i);
                    //flag that this iteration contains a valid tx. This will force the while loop to rerun once more.
                    validTxExists = true;
                    //update utxo pool with the outputs of new tx.
                    updateUTXOPool(tx);
                }
            }
        } while(validTxExists);

        //here we just convert the ArrayList to an Array so we can return it from this method.
        Transaction validTxs[] = new Transaction[validTxsList.size()];
        validTxs = validTxsList.toArray(validTxs);
        return validTxs;
    }

    //this is a helper function to convert an array to an ArrayList. ArrayList allows
    //items to be added to it dynamically.
    private ArrayList<Transaction> toArrayList(Transaction[] txs)
    {
        ArrayList<Transaction> txsList = new ArrayList<Transaction>();
        for (int i = 0; i < txs.length; i++)
        {
            //make sure to filter out null txs
            if (txs[i] != null)
            {
                txsList.add(txs[i]);
            }
        }
        return txsList;
    }

    //this is called when we know that the tx is valid, so all the 
    //tx inputs should be in the utxo pool at this point.
    private void updateUTXOPool(Transaction tx)
    {
        //step 1. remove each utxo that matches the inputs of the tx, thus
        //marking that UTXO as claimed.
        for(int i = 0; i < tx.numInputs(); i++)
        {
            Transaction.Input input = tx.getInput(i);
            UTXO inputUTXO = new UTXO(input.prevTxHash, input.outputIndex);
            pool.removeUTXO(inputUTXO);
        }

        //step 2. Build a new UTXO for the outputs in the current tx and add it to 
        //the UTXO pool. The outputs of the current tx will be considered the new UTXO
        //that can be claimed later.
        for(int i = 0; i < tx.numOutputs(); i++)
        {
            Transaction.Output output = tx.getOutput(i);
            UTXO outputUTXO = new UTXO(tx.getHash(), i);
            pool.addUTXO(outputUTXO, output);
        }
    }
}
