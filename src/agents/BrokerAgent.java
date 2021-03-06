package agents;

import jade.core.*;
import jade.core.behaviours.CyclicBehaviour;
import jade.core.behaviours.FSMBehaviour;
import jade.core.behaviours.OneShotBehaviour;
import jade.core.behaviours.ParallelBehaviour;
import jade.core.behaviours.SequentialBehaviour;
import jade.core.behaviours.SimpleBehaviour;
import jade.core.behaviours.WakerBehaviour;
import jade.domain.*;
import jade.domain.FIPAAgentManagement.*;
import jade.lang.acl.*;
import jade.proto.AchieveREInitiator;
import jade.proto.AchieveREResponder;
import jade.proto.SubscriptionInitiator;
import jade.content.ContentElement;
import jade.content.lang.*;
import jade.content.lang.sl.*;
import jade.content.onto.*;
import jade.content.onto.basic.Action;
import jade.content.AgentAction;
import jade.content.Concept;

import java.awt.Color;
import java.util.*;
import java.util.Map.Entry;

import behaviours.DelayBehaviour;
import gui.ProgramGUI;
import ontologies.*;
import utility.*;

@SuppressWarnings("serial")
public class BrokerAgent extends Agent implements SupplierVocabulary {
	private Broker broker;
	
	// Agents
	private HashMap<AID, Quote> retailers = new HashMap<AID, Quote>();
	private Queue<Integer> priceData = new CircularFifoQueue<Integer>(100);
	private AID bestOffer;
	
	// Language
	private Codec codec = new SLCodec();
	private Ontology ontology = SupplierOntology.getInstance();

	@Override
	protected void setup() {
		// Register language and ontology
		getContentManager().registerLanguage(codec);
		getContentManager().registerOntology(ontology);
		
		Object[] args = getArguments();
		if(args != null && args.length > 0) {
			broker = (Broker)args[0];
		} else {
			broker = new Broker("Broker");
		}
		
		ProgramGUI.getInstance().printToLog(broker.hashCode(), getLocalName(), "created", Color.GREEN.darker());
		
		// Register in the DF
		DFRegistry.register(this, BROKER_AGENT);
		
		subscribeToRetailers();
		query();
		purchase();
	}
	
	@Override
	protected void takeDown() {
		ProgramGUI.getInstance().printToLog(broker.hashCode(), getLocalName(), "shutdown", Color.RED.darker());
		try { DFService.deregister(this); } catch (Exception e) { e.printStackTrace(); };
	}
	
	void query() {
		ProgramGUI.getInstance().printToLog(broker.hashCode(), getLocalName(), "awaiting queries...", Color.ORANGE.darker());
		
		MessageTemplate template = MessageTemplate.and(
		  		MessageTemplate.MatchProtocol(FIPANames.InteractionProtocol.FIPA_REQUEST),
		  		MessageTemplate.MatchPerformative(ACLMessage.QUERY_REF) );
		
		AchieveREResponder arer = new AchieveREResponder(this, template) {
			@Override
			protected ACLMessage handleRequest(ACLMessage request) throws NotUnderstoodException, RefuseException {
				ProgramGUI.getInstance().printToLog(broker.hashCode(),  broker.getName(), ">> query received", Color.GREEN.darker());
				
				try {
					Thread.sleep(1000);
				} catch (InterruptedException e) {
					e.printStackTrace();
				}

				// Request fails if their are no retailers
				ACLMessage response = request.createReply();
				
				if(retailers.size() > 0) {
					response.setPerformative(ACLMessage.AGREE);
				} else {
					response.setPerformative(ACLMessage.REFUSE);
				}
				
				return response;
			}
			
			@Override
			protected ACLMessage prepareResultNotification(ACLMessage request, ACLMessage response) throws FailureException {
				ProgramGUI.getInstance().printToLog(broker.hashCode(), getLocalName(), "<< returning best price", Color.GREEN.darker());
				ACLMessage result = request.createReply();
				result.setPerformative(ACLMessage.INFORM);
				
				try {
					Thread.sleep(1000);
				} catch (InterruptedException e) {
					e.printStackTrace();
				}
			
				try {
					ContentElement content = getContentManager().extractContent(request);
					Exchange e = (Exchange)((Action)content).getAction();
					
					AID optimal = null;
					AID subOptimal = null;
					
					int optimalPrice = 0;
					int optimalBuyPrice = Integer.MAX_VALUE;
					int optimalSellPrice = 0;
					int subOptimalPrice = 0;
					int subOptimalSupply = 0;
					
					// Try and find the best retailer based on homes wants
					for(Map.Entry<AID, Quote> cursor : retailers.entrySet()) {
						AID retailer = cursor.getKey();
						int sellPrice = cursor.getValue().getSellPrice();
						int buyPrice = cursor.getValue().getBuyPrice();
						int supply = cursor.getValue().getUnits();
						
						// find retailer with the best price that has stock available.
						if(e.getType() == BUY) {
							System.out.println(getLocalName() + ": Home wants to purchase " + e.getUnits() + " units.");
							
							if(supply >= e.getUnits()) {
								if(sellPrice < optimalPrice) {
									optimal = retailer;
									optimalBuyPrice = sellPrice;
								}
							}
							
							if(supply > subOptimalSupply) {
								subOptimalSupply = supply;
								subOptimalPrice = sellPrice;
								subOptimal = retailer;
							}
							
							optimalPrice = optimalBuyPrice;
						
						} else if (e.getType() == SELL) {
							System.out.println(getLocalName() + ": Home wants to sell " + e.getUnits() + " units.");
							
							if(buyPrice > optimalSellPrice) {
								optimal = retailer;
								optimalSellPrice = buyPrice;
							}
							
							optimalPrice = optimalSellPrice;
						}
					}
					
					// Prepare exchange object to be sent back
					Exchange eResult = new Exchange();
					eResult.setType(e.getType());
					eResult.setValue(AVERAGE);
					
					// if no retailers have sufficient stock choose the retailer with the most stock
					if(optimal != null) {
						System.out.println("Optimal retailer: " + optimal.getLocalName());
						bestOffer = optimal;
						eResult.setPrice(optimalPrice);
						eResult.setUnits(e.getUnits());
						e.setValue(getExchangeValue(optimalPrice));
					} else {
						bestOffer = subOptimal;
						eResult.setPrice(subOptimalPrice);
						eResult.setUnits(subOptimalSupply);
						e.setValue(getExchangeValue(subOptimalPrice));
					}	
					
					getContentManager().fillContent(result, new Action(request.getSender(), eResult));
				} catch(Exception e) {
					e.printStackTrace();
				}
				
				return result;
			}
			
			@Override 
			public int onEnd() {
				System.out.println(getLocalName() + ": \"start\" state finished with exit code 1");
				return 1;
			}
		};
		
		addBehaviour(arer);
	}
	
	void purchase() {
		ProgramGUI.getInstance().printToLog(broker.hashCode(), getLocalName(), "awaiting requests...", Color.ORANGE.darker());
		
	  	MessageTemplate template = MessageTemplate.and(
	  		MessageTemplate.MatchProtocol(FIPANames.InteractionProtocol.FIPA_REQUEST),
	  		MessageTemplate.MatchPerformative(ACLMessage.REQUEST) );
	  		
		AchieveREResponder arer = new AchieveREResponder(this, template) {
			@Override
			protected ACLMessage handleRequest(ACLMessage request) throws NotUnderstoodException, RefuseException {
				ProgramGUI.getInstance().printToLog(broker.hashCode(), getLocalName(), ">> received request", Color.GREEN.darker());
				
				
				// sleep
				try {
					Thread.sleep(1000);
				} catch (InterruptedException e) {
					e.printStackTrace();
				}
			
				// Request fails if their are no retailers
				ACLMessage response = request.createReply();
			
				if(retailers.size() > 0) {
					response.setPerformative(ACLMessage.AGREE);
				} else {
					response.setPerformative(ACLMessage.REFUSE);
					throw new RefuseException("no-retailers");
				}
				
				return response;
			}
		};
			
		// Register an AchieveREInitiator in the PREPARE_RESULT_NOTIFICATION state
		arer.registerPrepareResultNotification(new AchieveREInitiator(this, null) {
			@Override
			protected Vector<ACLMessage> prepareRequests(ACLMessage request) {
				try {
					// Retrieve the incoming request from the DataStore
					String incomingRequestKey = (String) ((AchieveREResponder) parent).REQUEST_KEY;
					ACLMessage incomingRequest = (ACLMessage) getDataStore().get(incomingRequestKey);
					// Prepare the request to forward to the responder
					ProgramGUI.getInstance().printToLog(broker.hashCode(), getLocalName(), "forwarding request >>"+ bestOffer.getLocalName(), Color.GREEN.darker());
					
					ACLMessage outgoingRequest = new ACLMessage(ACLMessage.REQUEST);
					outgoingRequest.setProtocol(FIPANames.InteractionProtocol.FIPA_REQUEST);
					outgoingRequest.addReceiver(bestOffer);
					outgoingRequest.setContent(incomingRequest.getContent());
					outgoingRequest.setReplyByDate(incomingRequest.getReplyByDate());
					Vector<ACLMessage> v = new Vector<ACLMessage>(1);
					v.addElement(outgoingRequest);
					return v;
				} catch(Exception e) {
					e.printStackTrace();
					ProgramGUI.getInstance().printToLog(broker.hashCode(),  getLocalName(),
							"Failed to\nforward request >>", Color.RED.darker());
					return null;
				}
			}
			
			@Override
			protected void handleInform(ACLMessage inform) {
				storeNotification(ACLMessage.INFORM);
			}
			
			@Override
			protected void handleRefuse(ACLMessage refuse) {
				storeNotification(ACLMessage.FAILURE);
			}
			
			@Override
			protected void handleNotUnderstood(ACLMessage notUnderstood) {
				storeNotification(ACLMessage.FAILURE);
			}
			
			@Override
			protected void handleFailure(ACLMessage failure) {
				storeNotification(ACLMessage.FAILURE);
			}
			
			@Override
			protected void handleAllResultNotifications(@SuppressWarnings("rawtypes") Vector notifications) {
				// sleep
				try {
					Thread.sleep(1000);
				} catch (InterruptedException e) {
					e.printStackTrace();
				}
				
				if (notifications.size() == 0) {
					// Timeout
					storeNotification(ACLMessage.FAILURE);
				}
			}
			
			private void storeNotification(int performative) {
				if (performative == ACLMessage.INFORM) {
					ProgramGUI.getInstance().printToLog(broker.hashCode(), getLocalName(), 
							"brokerage successful", Color.GREEN.darker());
				}
				else {
					ProgramGUI.getInstance().printToLog(broker.hashCode(), getLocalName(), 
							"brokerage failed", Color.RED.darker());
				}
					
				// Retrieve the incoming request from the DataStore
				String incomingRequestkey = (String) ((AchieveREResponder) parent).REQUEST_KEY;
				ACLMessage incomingRequest = (ACLMessage) getDataStore().get(incomingRequestkey);
				// Prepare the notification to the request originator and store it in the DataStore
				ACLMessage notification = incomingRequest.createReply();
				notification.setPerformative(performative);
				String notificationkey = (String) ((AchieveREResponder) parent).RESULT_NOTIFICATION_KEY;
				getDataStore().put(notificationkey, notification);
			}
		});

		addBehaviour(arer);
  	}
	
	public int getExchangeValue(int price) {
		int avg;
		int total = 0;
		int count = 0;
		for(Iterator it = priceData.iterator(); it.hasNext();) {
			total += (int)it.next();
			count++;
		}
		avg = total / count;
		
		int result;
		
		if(avg / price >= 1.1) {
			result = CHEAP;
		} else if (avg / price <= 0.9) {
			result = EXPENSIVE;
		} else {
			result = AVERAGE;
		}
		
		return result;
	}
	
	
	// -- Utility Methods --
	void subscribeToRetailers() {
		DFAgentDescription dfd = new DFAgentDescription();
		ServiceDescription sd = new ServiceDescription();
		sd.setType(RETAILER_AGENT);
		dfd.addServices(sd);
	
		// Handle registration of new retailers
  		addBehaviour(new SubscriptionInitiator(this,
			DFService.createSubscriptionMessage(this, getDefaultDF(), dfd, null)) {
  			
  			@Override
  			protected void handleInform(ACLMessage inform) {
  				try {
  					ACLMessage retailSub = new ACLMessage(ACLMessage.QUERY_REF);
  					retailSub.setProtocol(FIPANames.InteractionProtocol.FIPA_SUBSCRIBE);
  					retailSub.setLanguage(codec.getName());
  					retailSub.setOntology(ontology.getName());
  					
  					DFAgentDescription[] dfds = DFService.decodeNotification(inform.getContent());
  					DFAgentDescription[] df = DFService.search(myAgent, dfd);
  					
  					// Register additional price update subscription
  					SubscriptionInitiator priceChanges = new SubscriptionInitiator(myAgent, retailSub) {		
  						@Override
  						protected void handleInform(ACLMessage inform) {
  							try {
	  							ContentElement content = getContentManager().extractContent(inform);
	  							Quote quote = (Quote)((Action)content).getAction();
	  							
	  							//TODO Confirm
	  							ProgramGUI.getInstance().printToLog(broker.hashCode(), getLocalName(),
	  									inform.getSender().getLocalName() + " price now $" +
  											quote.getSellPrice() + "/unit", Color.BLUE.darker());
	  							
	  							retailers.put(inform.getSender(), quote);
	  							priceData.add(quote.getSellPrice()); // track price averages
	  							
	  							// sleep
								Thread.sleep(1000);
								
  							} catch(Exception e) {
  								e.printStackTrace();
  							}
  						}
  					};
  					
  					// Check agent in the inform message exists before adding it
					for(int i = 0; i < dfds.length; i++) {
						boolean exists = false;
						
						for(int j = 0; j < df.length; j++) {
							if(dfds[i].getName().hashCode() == df[j].getName().hashCode()) {
								exists = true;
								break;
							}
						}
						
						removeBehaviour(priceChanges);
						priceChanges.reset(retailSub);
						
						if(exists) {
							System.out.println(getLocalName() + ": new retailer " + dfds[i].getName());
							//TODO Confirm
//							ProgramGUI.getInstance().printToLog(broker.hashCode(), getLocalName(), 
//									"new retailer " + dfds[i].getName(), Color.GREEN.darker());
							ProgramGUI.getInstance().printToLog(broker.hashCode(), getLocalName(), 
									"new retailer detected", Color.BLUE.darker());
				
							if (retailers.size() == 0) { 
								System.out.println(getLocalName() + ": listening for price changes from " + dfds[i].getName());
								ProgramGUI.getInstance().printToLog(broker.hashCode(), getLocalName(), 
										"listening for\nprice changes...", Color.BLUE.darker());
							}
							
							retailers.put(dfds[i].getName(), null);
							retailSub.addReceiver(dfds[i].getName());
							
							addBehaviour(priceChanges);
						} else {
							//TODO Confirm
//							ProgramGUI.getInstance().printToLog(broker.hashCode(), getLocalName(), 
//									"stopped listening..." + dfds[i].getName(), Color.RED);
							ProgramGUI.getInstance().printToLog(broker.hashCode(), getLocalName(), 
									"stopped listening...", Color.RED.darker());

							if(retailers.size() < 1) { 
								ProgramGUI.getInstance().printToLog(broker.hashCode(), getLocalName(), 
										"stopped listening...", Color.RED.darker());
							}
							
							retailers.remove(dfds[i].getName());
							retailSub.removeReceiver(dfds[i].getName());
						}
					}
  				} catch (Exception e) {
  					e.printStackTrace();
  				}
  			}
		});
	}
}