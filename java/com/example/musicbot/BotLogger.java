package com.example.musicbot;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.Marker;
import org.slf4j.MarkerFactory;
import org.slf4j.MDC;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Logging system for the Music Bot
 */
public class BotLogger {
    // Main logger
    private static final Logger MAIN = LoggerFactory.getLogger("MusicBot");
    private static final Logger AUDIO = LoggerFactory.getLogger("MusicBot.Audio");
    private static final Logger COMMANDS = LoggerFactory.getLogger("MusicBot.Commands");
    
    // Markers for special log messages
    private static final Marker CONSOLE = MarkerFactory.getMarker("CONSOLE");
    private static final Marker PERFORMANCE = MarkerFactory.getMarker("PERFORMANCE");
    
    // Track operation timings (simple implementation)
    private static final ConcurrentHashMap<String, Long> operationTimers = new ConcurrentHashMap<>();
    
    /**
     * Log a bot status message that will appear in the console
     */
    public static void status(String message) {
        MAIN.info(CONSOLE, message);
    }
    
    /**
     * Log audio-related events
     */
    public static void audio(String message) {
        AUDIO.info(message);
    }
    
    /**
     * Log command execution
     */
    public static void command(String guild, String user, String command, String args) {
        MDC.put("guild", guild);
        MDC.put("user", user);
        MDC.put("command", command);
        COMMANDS.info("Command executed: {} with args: {}", command, args);
        MDC.clear();
    }
    
    /**
     * Start timing an operation (for performance tracking)
     * @return operation ID for stopping the timer
     */
    public static String startOperation(String operationName) {
        String operationId = operationName + "_" + System.currentTimeMillis();
        operationTimers.put(operationId, System.currentTimeMillis());
        return operationId;
    }
    
    /**
     * Stop timing an operation and log the duration
     */
    public static void stopOperation(String operationId, String operationName) {
        if (operationId == null || operationId.isEmpty()) return;
        
        Long startTime = operationTimers.remove(operationId);
        if (startTime != null) {
            long duration = System.currentTimeMillis() - startTime;
            MAIN.debug(PERFORMANCE, "Operation completed: {} took {}ms", operationName, duration);
        }
    }
    
    /**
     * Log debug information
     */
    public static void debug(String message) {
        MAIN.debug(message);
    }
    
    /**
     * Log error message
     */
    public static void error(String message) {
        MAIN.error(message);
    }
    
    /**
     * Log error message with exception
     */
    public static void error(String message, Throwable throwable) {
        MAIN.error(message, throwable);
    }
    
    /**
     * Log info message
     */
    public static void info(String message) {
        MAIN.info(message);
    }
    
    /**
     * Log warning message
     */
    public static void warn(String message) {
        MAIN.warn(message);
    }
    
    /**
     * Backward compatibility for Bot.java's logClean method
     */
    public static void logClean(String message) {
        status(message);
    }
    
    /**
     * Backward compatibility for Bot.java's logInfo method
     */
    public static void logInfo(String message) {
        info(message);
    }
    
    /**
     * Backward compatibility for Bot.java's logDebug method
     */
    public static void logDebug(String message) {
        debug(message);
    }
    
    /**
     * Backward compatibility for other log methods
     */
    public static void logTrace(String message) {
        MAIN.trace(message);
    }
}