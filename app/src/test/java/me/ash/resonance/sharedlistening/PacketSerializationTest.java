package me.ash.resonance.sharedlistening;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import com.google.gson.Gson;

import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

import me.ash.resonance.sharedlistening.transport.model.Packet;

public class PacketSerializationTest {
    private final Gson gson = new Gson();

    @Test
    public void testPacketSerialization() {
        Map<String, Object> data = new HashMap<>();
        data.put("testKey", "testValue");
        Packet packet = new Packet(Packet.TYPE_METADATA, data);

        String json = gson.toJson(packet);
        assertNotNull(json);

        Packet deserialized = gson.fromJson(json, Packet.class);
        assertEquals(Packet.TYPE_METADATA, deserialized.type);
        assertEquals(Packet.VERSION, deserialized.version);
        assertNotNull(deserialized.data);
        
        // Gson deserializes numeric data as Double by default if type is Object
        Map<String, Object> deserializedData = (Map<String, Object>) deserialized.data;
        assertEquals("testValue", deserializedData.get("testKey"));
    }
}
