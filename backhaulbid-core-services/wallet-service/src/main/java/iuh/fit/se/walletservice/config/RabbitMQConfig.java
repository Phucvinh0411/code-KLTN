package iuh.fit.se.walletservice.config;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitMQConfig {

    public static final String WALLET_EVENTS_EXCHANGE = "wallet.events";

    public static final String UNFREEZE_LOSERS_QUEUE = "auction.unfreeze_losers.queue";
    public static final String UNFREEZE_LOSERS_ROUTING_KEY = "auction.unfreeze_losers";

    public static final String REFUND_CREATION_FEE_QUEUE = "auction.refund_creation_fee.queue";
    public static final String REFUND_CREATION_FEE_ROUTING_KEY = "auction.refund_creation_fee";

    @Bean
    public TopicExchange walletEventsExchange() {
        return new TopicExchange(WALLET_EVENTS_EXCHANGE, true, false);
    }

    @Bean
    public Queue unfreezeLosersQueue() {
        return QueueBuilder.durable(UNFREEZE_LOSERS_QUEUE).build();
    }

    @Bean
    public Binding unfreezeLosersBinding(Queue unfreezeLosersQueue, TopicExchange walletEventsExchange) {
        return BindingBuilder
                .bind(unfreezeLosersQueue)
                .to(walletEventsExchange)
                .with(UNFREEZE_LOSERS_ROUTING_KEY);
    }

    @Bean
    public Queue refundCreationFeeQueue() {
        return QueueBuilder.durable(REFUND_CREATION_FEE_QUEUE).build();
    }

    @Bean
    public Binding refundCreationFeeBinding(Queue refundCreationFeeQueue, TopicExchange walletEventsExchange) {
        return BindingBuilder
                .bind(refundCreationFeeQueue)
                .to(walletEventsExchange)
                .with(REFUND_CREATION_FEE_ROUTING_KEY);
    }

    @Bean
    public MessageConverter jacksonMessageConverter() {
        return new Jackson2JsonMessageConverter();
    }

    @Bean
    public RabbitTemplate rabbitTemplate(ConnectionFactory connectionFactory) {
        RabbitTemplate template = new RabbitTemplate(connectionFactory);
        template.setMessageConverter(jacksonMessageConverter());
        return template;
    }
}
