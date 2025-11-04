package org.openfilz.dms.utils;

import org.xml.sax.helpers.DefaultHandler;
import reactor.core.publisher.FluxSink;

/**
 * A Tika ContentHandler that bridges Tika's push-based parsing
 * to a reactive Flux by emitting string chunks to a FluxSink.
 */
public class FluxSinkContentHandler extends DefaultHandler {

    private final FluxSink<String> sink;

    public FluxSinkContentHandler(FluxSink<String> sink) {
        this.sink = sink;
    }

    /**
     * This method is called by the Tika parser with chunks of character data.
     * We push each non-empty chunk into the Flux sink.
     */
    @Override
    public void characters(char[] ch, int start, int length) {
        String text = new String(ch, start, length).trim();
        if (!text.isEmpty()) {
            sink.next(text);
        }
    }
}