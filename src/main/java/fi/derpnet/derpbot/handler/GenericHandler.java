package fi.derpnet.derpbot.handler;

import fi.derpnet.derpbot.controller.MainController;

/**
 * A generic handler for things.
 */
public interface GenericHandler {

    /**
     * Called upon initializing this instance for use
     *
     * @param controller the controller that will be handling this handler
     */
    void init(MainController controller);

    /**
     * Gets the command (prefix) used to invoke this handler. Used in printing
     * the list of available commands.
     *
     * @return The command (prefix), or null if this handler serves no command
     */
    String getCommand();

    /**
     * Gets the help line for this handler, used in printing the help output for
     * the command.
     *
     * @return The help line, or null if there's no help
     */
    String getHelp();
}
