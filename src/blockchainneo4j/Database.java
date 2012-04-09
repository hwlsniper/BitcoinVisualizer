package blockchainneo4j;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.neo4j.rest.graphdb.RestAPI;
import org.neo4j.rest.graphdb.entity.RestNode;

import org.neo4j.graphdb.Direction;
import org.neo4j.graphdb.Node;
import org.neo4j.graphdb.Path;
import org.neo4j.graphdb.Relationship;
import org.neo4j.graphdb.RelationshipType;
import org.neo4j.graphdb.traversal.TraversalDescription;
import org.neo4j.graphdb.traversal.Traverser;
import org.neo4j.kernel.impl.traversal.TraversalDescriptionImpl;

import blockchainneo4j.domain.BlockType;
import blockchainneo4j.domain.InputType;
import blockchainneo4j.domain.LatestBlock;
import blockchainneo4j.domain.OutputType;
import blockchainneo4j.domain.PrevOut;
import blockchainneo4j.domain.TransactionType;

/**
 * Stores the basic underlying structure of the bitcoin blockchcain to neo4j. The relationships are:
 * Blocks "succeed" one another.  Transaction are "from" blocks.  Transactions "send" money.  Transactions "receive" money.
 * @author John
 *
 */
public class Database
{
	private static final Logger LOG = Logger.getLogger(Database.class.getName());

	RestAPI restApi;

	/**
	 * Represents the basic relationships of the model.
	 * @author John
	 *
	 */
	enum BitcoinRelationships implements RelationshipType
	{
		succeeds, from, received, sent
	}

	public Database(String uri)
	{
		restApi = new RestAPI(uri);
	}

	public Database(String uri, String user, String pass)
	{
		restApi = new RestAPI(uri, user, pass);
	}

	/**
	 * Stores blocks, transactions, and input/output nodes to the database.
	 * Blocks will be given a relationship of "succeeds" to the previous
	 * categorized block to the database. Transactions will be given a
	 * relationship of "from" to its owner Block. Input/Output nodes (called
	 * Money nodes) will either be given "sent" or "received" relationships
	 * based upon what they represent.
	 * 
	 * @author John
	 * 
	 */
	public void downloadBlockChain()
	{
		// Last block in the chain that has a relationship from the database.
		// Will return the reference node if none exist.
		Node lastBlockNode = getLatestLocalBlockNode();

		// We now get the index number of this particular block
		int lastBlockIndex;
		if (lastBlockNode.hasProperty("block_index"))
			lastBlockIndex = (Integer) lastBlockNode.getProperty("block_index");
		else
			lastBlockIndex = 0;

		// Fetch the latest block index from the API
		LatestBlock latestBlock = Fetcher.GetLatest();
		if (latestBlock == null)
		{
			LOG.severe("Unable to retreive the latest block index.  Aborting.");
			return;
		}
		final int LATESTBLOCKINDEX = latestBlock.getBlock_index();

		// Begin persistence
		BlockType currentBlock = null;
		RestNode currentBlockNode = null;
		while (lastBlockIndex < LATESTBLOCKINDEX)
		{
			try
			{
				// Download the next block from the API
				currentBlock = Fetcher.GetBlock(lastBlockIndex + 1);
				// Persist a new block node
				Map<String, Object> blockProps = new HashMap<String, Object>();
				blockProps.put("hash", currentBlock.getHash());
				blockProps.put("ver", currentBlock.getVer());
				blockProps.put("prev_block", currentBlock.getPrev_block());
				blockProps.put("mrkl_root", currentBlock.getMrkl_root());
				blockProps.put("time", currentBlock.getTime());
				blockProps.put("bits", currentBlock.getBits());
				blockProps.put("nonce", currentBlock.getNonce());
				blockProps.put("n_tx", currentBlock.getN_tx());
				blockProps.put("size", currentBlock.getSize());
				blockProps.put("block_index", currentBlock.getBlock_index());
				blockProps.put("main_chain", currentBlock.getMain_chain());
				blockProps.put("height", currentBlock.getHeight());
				blockProps.put("received_time", currentBlock.getReceived_time());
				blockProps.put("relayed_by", currentBlock.getRelayed_by());
				currentBlockNode = restApi.createNode(blockProps);

				// Create a relationship of this block to the parentBlock
				restApi.createRelationship(currentBlockNode, lastBlockNode, BitcoinRelationships.succeeds, null);

				// Persist transaction nodes
				// The transaction node properties
				Map<String, Object> tranProps;
				// The transaction object
				TransactionType tran;
				// The transaction node
				RestNode tranNode = null;
				// The relationship properties between transaction and block
				Map<String, Object> fromRelation;
				for (Iterator<TransactionType> tranIter = currentBlock.getTx().iterator(); tranIter.hasNext();)
				{
					tranProps = new HashMap<String, Object>();
					tran = tranIter.next();
					tranProps.put("hash", tran.getHash());
					tranProps.put("ver", tran.getVer());
					tranProps.put("vin_sz", tran.getVin_sz());
					tranProps.put("vout_sz", tran.getVout_sz());
					tranProps.put("size", tran.getSize());
					tranProps.put("relayed_by", tran.getRelayed_by());
					tranProps.put("tx_index", tran.getTx_index());
					tranNode = restApi.createNode(tranProps);

					fromRelation = new HashMap<String, Object>();
					fromRelation.put("block_hash", currentBlock.getHash());
					restApi.createRelationship(tranNode, currentBlockNode, BitcoinRelationships.from, fromRelation);

					// Persist Money nodes 
					// The money node properties
					Map<String, Object> moneyProps; 
					// The money node
					RestNode outNode = null; 
					// The output object
					OutputType output;
					// The relationship properties between transaction and output
					Map<String, Object> sentRelation;
					// The location of an output within a transaction.
					int n = 0;
					for (Iterator<OutputType> outputIter = tran.getOut().iterator(); outputIter.hasNext();)
					{
						moneyProps = new HashMap<String, Object>();
						output = outputIter.next();
						moneyProps.put("type", output.getType());
						moneyProps.put("addr", output.getAddr());
						moneyProps.put("value", output.getValue());
						moneyProps.put("n", n);
						outNode = restApi.createNode(moneyProps);

						sentRelation = new HashMap<String, Object>();
						sentRelation.put("to_addr", output.getAddr());
						sentRelation.put("n", n);						
						restApi.createRelationship(tranNode, outNode, BitcoinRelationships.sent, sentRelation);
						n++;
					}					

					// The relationship properties between input and
					// transaction
					Map<String, Object> receivedRelation;
					// The input object
					PrevOut prevOut; 
					for (Iterator<InputType> inputIter = tran.getInputs().iterator(); inputIter.hasNext();)
					{
						moneyProps = new HashMap<String, Object>();
						prevOut = inputIter.next().getPrev_out();
						if (prevOut == null)
							continue;
						moneyProps.put("type", prevOut.getType());
						moneyProps.put("addr", prevOut.getAddr());
						moneyProps.put("value", prevOut.getValue());
						moneyProps.put("n", prevOut.getN());
						
						// We need to reedeem an output transaction.  Because the chain is being built sequentially from early to later, this is possible.
						TraversalDescription td = new TraversalDescriptionImpl();
						td = td.breadthFirst();						
						Iterable<Node> nodeTraversal = td.traverse(tranNode).nodes();
						
						boolean isFound = false;
						for (Iterator<Node> iter = nodeTraversal.iterator(); iter.hasNext();)
						{
							Node transactionNode = iter.next();
							// We grab the transaction the money node we are looking for belongs to	
							if (transactionNode.hasProperty("tx_index"))							
							{
								int transactionIndex = (Integer) transactionNode.getProperty("tx_index");								
								if (transactionIndex == prevOut.getTx_index())
								{
									// We have found the transaction node.  Now we find the corresponding money node by looking at "sent" transactions 									
									Iterable<Relationship> moneyNodeRelationships = transactionNode.getRelationships(BitcoinRelationships.sent, Direction.OUTGOING);
									for (Iterator<Relationship> moneyIter = moneyNodeRelationships.iterator(); moneyIter.hasNext();)
									{
										// For each sent transaction, we get the nodes attached to it
										Node[] moneyNodes = moneyIter.next().getNodes();										
										for (int i = 0; i < moneyNodes.length; i++)
										{
											// Is this the money node were looking for!?
											if (moneyNodes[i].hasProperty("addr") && moneyNodes[i].hasProperty("n") && ((String)moneyNodes[i].getProperty("addr")).contains(prevOut.getAddr()) && ((Integer)moneyNodes[i].getProperty("n") == prevOut.getN()))
											{
											
												// We have found the money node that reedemed this one.  Create the relationship.
												receivedRelation = new HashMap<String, Object>();
												receivedRelation.put("tx_index", prevOut.getTx_index());
												restApi.createRelationship(moneyNodes[i], tranNode, BitcoinRelationships.received, receivedRelation);
												isFound = true;
												break;	
											}								
										
										}
										
										if (isFound)
											break;										
									}						
								}
							}
							if (isFound)
								break;
						}
					}
				}
			}

			catch (FetcherException e)
			{
				LOG.log(Level.WARNING, "FethcerThread failed.  Download on block: " + e.getFailedBlockIndex() + " will try again in 30 seconds...", e);
				
				// 30 second backoff to let the API chill its shit.
				try
				{
					Thread.sleep(30000);
				}

				catch (InterruptedException e2)
				{
					LOG.log(Level.SEVERE, "Thread.sleep() was interrupted.  Aborting...", e2);
				}
			}

			// Update the previous block node to the current one and repeat
			lastBlockIndex = currentBlock.getBlock_index();
			lastBlockNode = currentBlockNode;

			// A one second wait period for the API
			try
			{
				Thread.sleep(2000);
			}

			catch (InterruptedException e)
			{
				LOG.log(Level.SEVERE, "Thread.sleep() was interrupted.  Aborting...", e);
			}
		}

	}

	/**
	 * Queries the local database to find the latest stored block index. Used to
	 * set a lower bound on what Blocks to fetch from the API.
	 * 
	 * @return The next block index the local datastore needs to store.
	 */
	private Node getLatestLocalBlockNode()
	{
		Node referenceNode = restApi.getReferenceNode();
		TraversalDescription td = new TraversalDescriptionImpl();
		td = td.depthFirst().relationships(BitcoinRelationships.succeeds, Direction.INCOMING);

		Traverser traverser = td.traverse(referenceNode);
		for (Iterator<Path> iter = traverser.iterator(); iter.hasNext();)
		{
			Node node = iter.next().endNode();
			if (!node.hasRelationship(BitcoinRelationships.succeeds, Direction.INCOMING))
				return node;
		}
		return referenceNode;
	}
}