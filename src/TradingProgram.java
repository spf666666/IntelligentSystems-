import gui.*;

public class TradingProgram {
	public static void main(String[] args) {
		SettingsGUI settings = SettingsGUI.getInstance();
		settings.setVisible(true);
		
		ProgramGUI program = ProgramGUI.getInstance();
		while(true) {
			program.updateGui();
		}
	}
}
