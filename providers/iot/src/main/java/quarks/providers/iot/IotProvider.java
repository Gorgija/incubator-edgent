/*
Licensed to the Apache Software Foundation (ASF) under one
or more contributor license agreements.  See the NOTICE file
distributed with this work for additional information
regarding copyright ownership.  The ASF licenses this file
to you under the Apache License, Version 2.0 (the
"License"); you may not use this file except in compliance
with the License.  You may obtain a copy of the License at

  http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing,
software distributed under the License is distributed on an
"AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
KIND, either express or implied.  See the License for the
specific language governing permissions and limitations
under the License.
*/
package quarks.providers.iot;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

import com.google.gson.JsonObject;

import quarks.apps.iot.IotDevicePubSub;
import quarks.connectors.iot.Commands;
import quarks.connectors.iot.IotDevice;
import quarks.connectors.pubsub.service.ProviderPubSub;
import quarks.connectors.pubsub.service.PublishSubscribeService;
import quarks.execution.Configs;
import quarks.execution.DirectSubmitter;
import quarks.execution.Job;
import quarks.execution.services.ControlService;
import quarks.execution.services.ServiceContainer;
import quarks.providers.direct.DirectProvider;
import quarks.runtime.appservice.AppService;
import quarks.runtime.jsoncontrol.JsonControlService;
import quarks.topology.TStream;
import quarks.topology.Topology;
import quarks.topology.TopologyProvider;
import quarks.topology.services.ApplicationService;

/**
 * IoT provider supporting multiple topologies with a single connection to a
 * message hub. A provider that uses a single {@link IotDevice} to communicate
 * with an IoT scale message hub.
 * {@link quarks.connectors.pubsub.PublishSubscribe Publish-subscribe} is
 * used to allow multiple topologies to communicate through the single
 * connection.
 * 
 */
public abstract class IotProvider implements TopologyProvider,
 DirectSubmitter<Topology, Job> {
    
    private final TopologyProvider provider;
    private final DirectSubmitter<Topology, Job> submitter;
    
    private final List<Topology> systemApps = new ArrayList<>();

    private JsonControlService controlService = new JsonControlService();
    
    protected IotProvider() {   
        this(new DirectProvider());
    }
    
    protected IotProvider(DirectProvider provider) {
        this(provider, provider);
    }

    protected IotProvider(TopologyProvider provider, DirectSubmitter<Topology, Job> submitter) {
        this.provider = provider;
        this.submitter = submitter;
        
        registerControlService();
        registerApplicationService();
        registerPublishSubscribeService();
        
        createIotDeviceApp();
        createIotCommandToControlApp();
    }
    
    public ApplicationService getApplicationService() {
        return getServices().getService(ApplicationService.class);
    }
    
    @Override
    public ServiceContainer getServices() {
        return submitter.getServices();
    }
    
    @Override
    public final Topology newTopology() {
        return provider.newTopology();
    }
    @Override
    public final Topology newTopology(String name) {
        return provider.newTopology(name);
    }
    @Override
    public final Future<Job> submit(Topology topology) {
        return submitter.submit(topology);
    }
    @Override
    public final Future<Job> submit(Topology topology, JsonObject config) {
        return submitter.submit(topology, config);
    }

    protected void registerControlService() {
        getServices().addService(ControlService.class, getControlService());
    }

    protected void registerApplicationService() {
        AppService.createAndRegister(this, this);
    }
    protected void registerPublishSubscribeService() {
        getServices().addService(PublishSubscribeService.class, 
                new ProviderPubSub());
    }

    protected JsonControlService getControlService() {
        return controlService;
    }
    
    /**
     * Create application that connects to the message hub.
     * Subscribes to device events and sends them to the messages hub.
     * Publishes device commands from the message hub.
     * @see IotDevicePubSub
     * @see #getMessageHubDevice(Topology)
     */
    protected void createIotDeviceApp() {
        Topology topology = newTopology("QuarksIotDevice");
             
        IotDevice msgHub = getMessageHubDevice(topology);
        IotDevicePubSub.createApplication(msgHub);
        systemApps.add(topology);
    }
    
    /**
     * Create application connects {@code quarksControl} device commands
     * to the control service.
     * 
     * Subscribes to device
     * commands of type {@link Commands#CONTROL_SERVICE}
     * and sends the payload into the JSON control service
     * to invoke the control operation.
     */
    protected void createIotCommandToControlApp() {
        Topology topology = newTopology("QuarksIotCommandsToControl");
        
        IotDevice publishedDevice = IotDevicePubSub.addIotDevice(topology);

        TStream<JsonObject> controlCommands = publishedDevice.commands(Commands.CONTROL_SERVICE);
        controlCommands.sink(cmd -> {
            try {
                getControlService().controlRequest(cmd.getAsJsonObject(IotDevice.CMD_PAYLOAD));
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        
        systemApps.add(topology);
    }
    
    public void start() throws InterruptedException, ExecutionException {
        for (Topology topology : systemApps) {
            JsonObject config = new JsonObject();
            config.addProperty(Configs.JOB_NAME, topology.getName());
            submit(topology, config).get();
        }
    }

    protected abstract IotDevice getMessageHubDevice(Topology topology);
}
