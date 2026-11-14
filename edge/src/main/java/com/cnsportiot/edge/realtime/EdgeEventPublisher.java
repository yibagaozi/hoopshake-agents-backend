package com.cnsportiot.edge.realtime;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

/** 事件发布入口,隔开 Spring 的 ApplicationEventPublisher,便于替换实现 */
@Component
public class EdgeEventPublisher {

    private final ApplicationEventPublisher publisher;

    public EdgeEventPublisher(ApplicationEventPublisher publisher) {
        this.publisher = publisher;
    }

    public void publish(WsEventType type, Object payload) {
        publisher.publishEvent(new EdgeEvent(type, payload));
    }
}

