package com.harnex.gateway.webhook.outbound;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

import com.harnex.config.ProjectRuntime;

/**
 * Spring-managed lookup table mapping channel name → {@link ChannelOutboundClient}. Each gateway resolves its outbound
 * client through this registry (rather than autowiring the concrete impl directly) so a future channel addition is a
 * one-class drop-in without touching the gateway code.
 */
@Component
@ProjectRuntime
public class OutboundClientRegistry {

    private final Map<String, ChannelOutboundClient> byChannel = new ConcurrentHashMap<>();

    public OutboundClientRegistry(List<ChannelOutboundClient> clients) {
        for (ChannelOutboundClient c : clients) {
            byChannel.put(c.channel().toLowerCase(), c);
        }
    }

    public Optional<ChannelOutboundClient> forChannel(String channel) {
        if (channel == null)
            return Optional.empty();
        return Optional.ofNullable(byChannel.get(channel.toLowerCase()));
    }
}
