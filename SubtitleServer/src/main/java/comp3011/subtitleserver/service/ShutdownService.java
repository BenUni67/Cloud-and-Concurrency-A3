package comp3011.subtitleserver.service;

import java.util.concurrent.atomic.AtomicBoolean;

import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.stereotype.Service;

@Service
public class ShutdownService {
	// Stores a reference to current spring application
	// spring creates an ApplicationContext when program starts
	private final ConfigurableApplicationContext applicationContext;
	
	// Makes sure that only one shutdown request is accepted
	private final AtomicBoolean shutDown = new AtomicBoolean(false);
	
	public ShutdownService(ConfigurableApplicationContext applicationContext) {
		this.applicationContext = applicationContext;
	}
	
	public boolean requestShutdown() {
		// If a shutdown is already requested then it will return false
		if (!shutDown.compareAndSet(false, true)) {
			return false;
		}
		
		// Shutdown on a new thread so the HTTP response has time to be returned
		Thread shutdownThread = new Thread(() -> {
			try {
				Thread.sleep(200);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
			
			int exit = SpringApplication.exit(applicationContext);
			System.exit(exit);
		});
		
		shutdownThread.start();
		return true;
	}
}
