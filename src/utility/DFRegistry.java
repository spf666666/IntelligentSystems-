package utility;

import jade.core.Agent;
import jade.domain.DFService;
import jade.domain.FIPAAgentManagement.DFAgentDescription;
import jade.domain.FIPAAgentManagement.ServiceDescription;

public class DFRegistry {
	public static void register(Agent agent, String type) {
		ServiceDescription sd = new ServiceDescription();
		sd.setType(type);
		sd.setName(agent.getLocalName());
		
		DFAgentDescription dfd = new DFAgentDescription();
		dfd.setName(agent.getAID());
		dfd.addServices(sd);
		
		try {
			DFAgentDescription[] dfds = DFService.search(agent, dfd);
			if (dfds.length > 0) {
				DFService.deregister(agent, dfd);
			}
			DFService.register(agent, dfd);
			System.out.println(agent.getLocalName() + " registered.");
		} catch (Exception e) {
			System.out.println("Failed registering with DF! Shutting down...");
			e.printStackTrace();
			agent.doDelete();
		}
	}
}
