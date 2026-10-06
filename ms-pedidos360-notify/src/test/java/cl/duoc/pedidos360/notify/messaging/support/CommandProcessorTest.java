package cl.duoc.pedidos360.notify.messaging.support;

import cl.duoc.pedidos360.notify.dto.MessageEnvelope;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.rabbitmq.client.Channel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class CommandProcessorTest {

    private static final long TAG = 7L;

    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
    private Channel channel;
    private CommandProcessor processor;

    @BeforeEach
    void setUp() {
        channel = mock(Channel.class);
        processor = new CommandProcessor(mapper, true);
    }

    private static String json(String eventId, String payload) {
        return "{\"type\":\"email.send\",\"eventId\":" + (eventId == null ? "null" : "\"" + eventId + "\"")
                + ",\"timestamp\":\"2026-10-06T10:00:00Z\",\"traceId\":\"t-1\",\"correlationId\":\"1\",\"payload\":"
                + payload + "}";
    }

    private static Message message(String body, boolean redelivered) {
        MessageProperties props = new MessageProperties();
        props.setDeliveryTag(TAG);
        props.setRedelivered(redelivered);
        props.setConsumerQueue("q.cmd.email");
        return new Message(body.getBytes(StandardCharsets.UTF_8), props);
    }

    @Test
    void comandoValido_seProcesaYSeConfirmaConAck() throws Exception {
        AtomicInteger calls = new AtomicInteger();

        processor.process("email", message(json("e1", "{\"orderId\":1}"), false), channel,
                env -> calls.incrementAndGet());

        assertThat(calls.get()).isEqualTo(1);
        verify(channel).basicAck(TAG, false);
        verify(channel, never()).basicReject(anyLong(), anyBoolean());
    }

    @Test
    void eventIdDuplicado_seDescartaConAckSinReprocesar() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        Message first = message(json("dup", "{\"orderId\":1}"), false);

        processor.process("email", first, channel, env -> calls.incrementAndGet());
        processor.process("email", first, channel, env -> calls.incrementAndGet());

        assertThat(calls.get()).isEqualTo(1);
        verify(channel, org.mockito.Mockito.times(2)).basicAck(TAG, false);
    }

    @Test
    void cuerpoNoJson_vaDirectoALaDlq() throws Exception {
        AtomicInteger calls = new AtomicInteger();

        processor.process("email", message("esto no es json", false), channel, env -> calls.incrementAndGet());

        assertThat(calls.get()).isZero();
        verify(channel).basicReject(TAG, false);
        verify(channel, never()).basicAck(anyLong(), anyBoolean());
    }

    @Test
    void sinEventId_vaDirectoALaDlq() throws Exception {
        processor.process("email", message(json(null, "{}"), false), channel, env -> { });

        verify(channel).basicReject(TAG, false);
    }

    @Test
    void errorTransitorio_primeraVez_seReencolaUnaVez() throws Exception {
        processor.process("email", message(json("t1", "{}"), false), channel, env -> {
            throw new TransientProcessingException("proveedor caido");
        });

        verify(channel).basicNack(TAG, false, true);
        verify(channel, never()).basicReject(anyLong(), anyBoolean());
    }

    @Test
    void errorTransitorio_yaReentregado_vaALaDlq() throws Exception {
        processor.process("email", message(json("t2", "{}"), true), channel, env -> {
            throw new TransientProcessingException("proveedor caido");
        });

        verify(channel).basicReject(TAG, false);
        verify(channel, never()).basicNack(anyLong(), anyBoolean(), anyBoolean());
    }

    @Test
    void errorNoRecuperable_vaALaDlqSinReintento() throws Exception {
        processor.process("email", message(json("p1", "{}"), false), channel, env -> {
            throw new IllegalStateException("datos corruptos");
        });

        verify(channel).basicReject(TAG, false);
        verify(channel, never()).basicNack(anyLong(), anyBoolean(), anyBoolean());
    }

    @Test
    void ganchoDeDemo_failModePoison_vaALaDlq() throws Exception {
        AtomicInteger calls = new AtomicInteger();

        processor.process("email", message(json("d1", "{\"failMode\":\"poison\"}"), false), channel,
                (MessageEnvelope env) -> calls.incrementAndGet());

        assertThat(calls.get()).isZero();
        verify(channel).basicReject(TAG, false);
    }

    @Test
    void ganchoDeDemo_apagado_noInterfiere() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        CommandProcessor sinGancho = new CommandProcessor(mapper, false);

        sinGancho.process("email", message(json("d2", "{\"failMode\":\"poison\"}"), false), channel,
                env -> calls.incrementAndGet());

        assertThat(calls.get()).isEqualTo(1);
        verify(channel).basicAck(TAG, false);
    }
}
