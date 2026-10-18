package com.syncsphere.web;

import com.syncsphere.config.SyncSphereProperties;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Tells the browser which application instance served it. The UI shows this so that, in the
 * two-instance Docker setup, you can see that users on "app-1" and "app-2" still edit together.
 */
@RestController
public class InstanceInfoController {

    private final SyncSphereProperties properties;

    public InstanceInfoController(SyncSphereProperties properties) {
        this.properties = properties;
    }

    @GetMapping("/api/instance")
    public Map<String, String> instance() {
        return Map.of("instanceId", properties.instanceId());
    }
}
