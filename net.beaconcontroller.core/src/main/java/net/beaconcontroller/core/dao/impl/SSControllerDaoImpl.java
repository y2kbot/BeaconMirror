package net.beaconcontroller.core.dao.impl;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketAddress;
import java.nio.channels.SocketChannel;

import java.util.Date;
import java.util.HashMap;
import java.util.Map;

import org.openflow.protocol.OFFeaturesReply;
import org.openflow.protocol.OFPhysicalPort;
import org.openflow.util.HexString;
import org.openflow.util.U16;
import org.openflow.util.U32;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.beaconcontroller.core.IOFController;
import net.beaconcontroller.core.IOFSwitch;
import net.beaconcontroller.core.dao.IControllerDao;
import net.beaconcontroller.storage.IResultSet;
import net.beaconcontroller.storage.IStorageSource;
import net.beaconcontroller.storage.OperatorPredicate;
import net.beaconcontroller.storage.StorageException;

public class SSControllerDaoImpl implements IControllerDao {

    protected static Logger log = LoggerFactory.getLogger(SSControllerDaoImpl.class);

    private IOFController controller;
    private IStorageSource storageSource;
    
    private static final String CONTROLLER_TABLE_NAME = "controller_controller";
    private static final String CONTROLLER_ID = "id";
    private static final String CONTROLLER_LISTEN_ADDRESS = "listen_address";
    private static final String CONTROLLER_LISTEN_PORT = "listen_port";
    private static final String CONTROLLER_LAST_STARTUP = "last_startup";
    private static final String CONTROLLER_ACTIVE = "active";
    
    private static final String SWITCH_TABLE_NAME = "controller_switch";
    private static final String SWITCH_DATAPATH_ID = "dpid";
    private static final String SWITCH_SOCKET_ADDRESS = "socket_address";
    private static final String SWITCH_IP = "ip";
    private static final String SWITCH_CONTROLLER_ID = "controller_id";
    private static final String SWITCH_ACTIVE = "active";
    private static final String SWITCH_CONNECTED_SINCE = "connected_since";
    private static final String SWITCH_CAPABILITIES = "capabilities";
    private static final String SWITCH_BUFFERS = "buffers";
    private static final String SWITCH_TABLES = "tables";
    private static final String SWITCH_ACTIONS = "actions";
    
    private static final String PORT_TABLE_NAME = "controller_port";
    private static final String PORT_ID = "id";
    private static final String PORT_SWITCH = "switch_id";
    private static final String PORT_NUMBER = "number";
    private static final String PORT_HARDWARE_ADDRESS = "hardware_address";
    private static final String PORT_NAME = "name";
    private static final String PORT_CONFIG = "config";
    private static final String PORT_STATE = "state";
    private static final String PORT_CURRENT_FEATURES = "current_features";
    private static final String PORT_ADVERTISED_FEATURES = "advertised_features";
    private static final String PORT_SUPPORTED_FEATURES = "supported_features";
    private static final String PORT_PEER_FEATURES = "peer_features";
    
    public SSControllerDaoImpl() {
    }
    
    private void deactivateSwitches() {
        String controllerId = controller.getControllerId();
        String[] switchColumns = { SWITCH_DATAPATH_ID, SWITCH_CONTROLLER_ID, SWITCH_ACTIVE };
        String[] portColumns = { PORT_ID, PORT_SWITCH };
        IResultSet switchResultSet = null;
        try {
            switchResultSet = storageSource.executeQuery(SWITCH_TABLE_NAME, switchColumns,
                    new OperatorPredicate(SWITCH_CONTROLLER_ID, OperatorPredicate.Operator.EQ, controllerId), null);
            while (switchResultSet.next()) {
                IResultSet portResultSet = null;
                try {
                    String datapathId = switchResultSet.getString(SWITCH_DATAPATH_ID);
                    switchResultSet.setBoolean(SWITCH_ACTIVE, Boolean.FALSE);
                    portResultSet = storageSource.executeQuery(PORT_TABLE_NAME, portColumns,
                            new OperatorPredicate(PORT_SWITCH, OperatorPredicate.Operator.EQ, datapathId), null);
                    while (portResultSet.next()) {
                        portResultSet.deleteRow();
                    }
                    portResultSet.save();
                }
                catch (StorageException e) {
                    // Ignore exception.
                    // FIXME: Should log something here.
                }
                finally {
                    if (portResultSet != null)
                        portResultSet.close();
                }
            }
            switchResultSet.save();
        }
        catch (StorageException e) {
            // Ignore exception.
            // FIXME: Should log something here.
        }
        finally {
            if (switchResultSet != null)
                switchResultSet.close();
        }
    }
    
    @Override
    public void startedController(IOFController controller) {
        this.controller = controller;
        
        deactivateSwitches();
        
        // Write out the controller info to the storage source
        Map<String, Object> controllerInfo = new HashMap<String, Object>();
        String id = controller.getControllerId();
        controllerInfo.put(CONTROLLER_ID, id);
        String listenAddress = controller.getListenAddress();
        controllerInfo.put(CONTROLLER_LISTEN_ADDRESS, listenAddress);
        int listenPort = controller.getListenPort();
        controllerInfo.put(CONTROLLER_LISTEN_PORT, listenPort);
        Date startupDate = new Date();
        controllerInfo.put(CONTROLLER_LAST_STARTUP, startupDate);
        controllerInfo.put(CONTROLLER_ACTIVE, Boolean.TRUE);
        storageSource.updateRow(CONTROLLER_TABLE_NAME, controllerInfo);
    }
    
    @Override
    public void shutDownController(IOFController controller) {
        // Update the controller info in the storage source to be inactive
        Map<String, Object> controllerInfo = new HashMap<String, Object>();
        String id = controller.getControllerId();
        controllerInfo.put(CONTROLLER_ID, id);
        controllerInfo.put(CONTROLLER_ACTIVE, Boolean.FALSE);
        storageSource.updateRow(CONTROLLER_TABLE_NAME, controllerInfo);
        
        deactivateSwitches();
    }
    
    @Override
    public void addedSwitch(IOFSwitch sw) {
        // Obtain the row info for the switch
        Map<String, Object> switchInfo = new HashMap<String, Object>();
        Long datapathId = sw.getId();
        String datapathIdString = HexString.toHexString(datapathId);
        switchInfo.put(SWITCH_DATAPATH_ID, datapathIdString);
        String controllerId = controller.getControllerId();
        switchInfo.put(SWITCH_CONTROLLER_ID, controllerId);
        Date connectedSince = sw.getConnectedSince();
        switchInfo.put(SWITCH_CONNECTED_SINCE, connectedSince);
        SocketChannel channel = sw.getSocketChannel();
        Socket socket = channel.socket();
        SocketAddress socketAddress = socket.getRemoteSocketAddress();
        String socketAddressString = socketAddress.toString();
        switchInfo.put(SWITCH_SOCKET_ADDRESS, socketAddressString);
        if (socketAddress instanceof InetSocketAddress) {
            InetSocketAddress inetSocketAddress = (InetSocketAddress)socketAddress;
            InetAddress inetAddress = inetSocketAddress.getAddress();
            String ip = inetAddress.getHostAddress();
            switchInfo.put(SWITCH_IP, ip);
        }
        
        // Write out the switch features info
        OFFeaturesReply featuresReply = sw.getFeaturesReply();
        long capabilities = U32.f(featuresReply.getCapabilities());
        switchInfo.put(SWITCH_CAPABILITIES, capabilities);
        long buffers = U32.f(featuresReply.getBuffers());
        switchInfo.put(SWITCH_BUFFERS, buffers);
        long tables = U32.f(featuresReply.getTables());
        switchInfo.put(SWITCH_TABLES, tables);
        long actions = U32.f(featuresReply.getActions());
        switchInfo.put(SWITCH_ACTIONS, actions);
        switchInfo.put(SWITCH_ACTIVE, Boolean.TRUE);
        
        // Update the switch
        storageSource.updateRow(SWITCH_TABLE_NAME, switchInfo);
        
        // Update the ports
        for (OFPhysicalPort port: featuresReply.getPorts()) {
            addOrModifyPort(sw, port);
        }
    }
    
    @Override
    public void removedSwitch(IOFSwitch sw) {
        // Update the controller info in the storage source to be inactive
        Map<String, Object> switchInfo = new HashMap<String, Object>();
        Long datapathId = sw.getId();
        String datapathIdString = HexString.toHexString(datapathId);
        switchInfo.put(SWITCH_DATAPATH_ID, datapathIdString);
        //switchInfo.put(SWITCH_CONNECTED_SINCE, null);
        switchInfo.put(SWITCH_ACTIVE, Boolean.FALSE);
        storageSource.updateRow(SWITCH_TABLE_NAME, switchInfo);
    }

    private void addOrModifyPort(IOFSwitch sw, OFPhysicalPort port) {
        String datapathIdString = HexString.toHexString(sw.getId());
        Map<String, Object> portInfo = new HashMap<String, Object>();
        int portNumber = U16.f(port.getPortNumber());
        String id = datapathIdString + ":" + portNumber;
        portInfo.put(PORT_ID, id);
        portInfo.put(PORT_SWITCH, datapathIdString);
        portInfo.put(PORT_NUMBER, portNumber);
        byte[] hardwareAddress = port.getHardwareAddress();
        String hardwareAddressString = HexString.toHexString(hardwareAddress);
        portInfo.put(PORT_HARDWARE_ADDRESS, hardwareAddressString);
        String name = port.getName();
        portInfo.put(PORT_NAME, name);
        long config = U32.f(port.getConfig());
        portInfo.put(PORT_CONFIG, config);
        long state = U32.f(port.getState());
        portInfo.put(PORT_STATE, state);
        long currentFeatures = U32.f(port.getCurrentFeatures());
        portInfo.put(PORT_CURRENT_FEATURES, currentFeatures);
        long advertisedFeatures = U32.f(port.getAdvertisedFeatures());
        portInfo.put(PORT_ADVERTISED_FEATURES, advertisedFeatures);
        long supportedFeatures = U32.f(port.getSupportedFeatures());
        portInfo.put(PORT_SUPPORTED_FEATURES, supportedFeatures);
        long peerFeatures = U32.f(port.getPeerFeatures());
        portInfo.put(PORT_PEER_FEATURES, peerFeatures);
        storageSource.updateRow(PORT_TABLE_NAME, portInfo);
    }
    
    public void addedPort(IOFSwitch sw, OFPhysicalPort port) {
        addOrModifyPort(sw, port);
    }
    
    public void modifiedPort(IOFSwitch sw, OFPhysicalPort port) {
        addOrModifyPort(sw, port);
    }
    
    public void deletedPort(IOFSwitch sw, short portNumber) {
        String datapathIdString = HexString.toHexString(sw.getId());
        String id = datapathIdString + ":" + portNumber;
        storageSource.deleteRow(PORT_TABLE_NAME, id);
    }

    public void setStorageSource(IStorageSource storageSource) {
        this.storageSource = storageSource;
        storageSource.createTable(CONTROLLER_TABLE_NAME);
        storageSource.createTable(SWITCH_TABLE_NAME);
        storageSource.createTable(PORT_TABLE_NAME);
        storageSource.setTablePrimaryKeyName(CONTROLLER_TABLE_NAME, CONTROLLER_ID);
        storageSource.setTablePrimaryKeyName(SWITCH_TABLE_NAME, SWITCH_DATAPATH_ID);
        storageSource.setTablePrimaryKeyName(PORT_TABLE_NAME, PORT_ID);
    }
}
