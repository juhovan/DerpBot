package fi.derpnet.derpbot.handler;

import fi.derpnet.derpbot.bean.RawMessage;
import fi.derpnet.derpbot.constants.IrcConstants;
import fi.derpnet.derpbot.controller.MainController;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Adapter for using a SimpleMessageHandler in place of a RawMessageHandler
 */
public class RawMessageAdapter implements RawMessageHandler {

    private final SimpleMessageHandler handler;

    public RawMessageAdapter(SimpleMessageHandler handler) {
        this.handler = handler;
    }

    @Override
    public List<RawMessage> handle(RawMessage message) {
        if (message.command.equals("PRIVMSG")) {
            String incomingRecipient = message.parameters.get(0);
            String messageBody = message.parameters.get(1);
            String responseBody = handler.handle(message.prefix, incomingRecipient, messageBody);
            if (responseBody == null) {
                return null;
            }
            String responseRecipient;
            if (IrcConstants.validChannelPrefixes.contains(incomingRecipient.charAt(0))) {
                // message was sent to a channel, reply there
                responseRecipient = message.parameters.get(0);
            } else {
                // private message, reply to sender
                responseRecipient = message.prefix.split("!")[0];
            }
            responseBody = ':' + responseBody;
            return Collections.singletonList(new RawMessage(null, "PRIVMSG", Arrays.asList(responseRecipient, responseBody)));
        } else {
            return null;
        }
    }

    @Override
    public void init(MainController controller) {
        handler.init(controller);
    }

    @Override
    public String getHelp() {
        return handler.getHelp();
    }

}
