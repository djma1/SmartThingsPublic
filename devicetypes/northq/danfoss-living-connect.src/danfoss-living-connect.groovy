/**
 *
 *
 *  Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 *  in compliance with the License. You may obtain a copy of the License at:
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software distributed under the License is distributed
 *  on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License
 *  for the specific language governing permissions and limitations under the License.
 *
 * 
 */

metadata 
{
    definition (name: "Danfoss Living Connect", namespace: "NorthQ", author: "René Bechmann") 
    {
        capability "Thermostat Setpoint"
        capability "Battery"

        command "setHeatingSetpoint"
        command "setConfiguration"
        
        attribute "heatingSetpoint", "string"
        attribute "nextHeatingSetpoint", "string"
        attribute "battery", "string"
               
        fingerprint deviceId: "0x0804"
        fingerprint inClusters: "0x80, 0x46, 0x81, 0x72, 0x8F, 0x75, 0x43, 0x86, 0x84"
    }

    simulator {}

    tiles (scale: 2)
    {
        multiAttributeTile(name:"thermostatFull", type:"thermostat", width:6, height:4) 
        {
            tileAttribute("device.heatingSetpoint", key: "PRIMARY_CONTROL") 
            {
                attributeState("default", label:'${currentValue}°', unit:"",
                      backgroundColors:[
                           [value: 0, color: "#ededed"],
                           [value: 4, color: "#153591"],
                           [value: 16, color: "#178998"],
                           [value: 18, color: "#199f5c"],
                           [value: 20, color: "#2da71c"],
                           [value: 21, color: "#5baa1d"],
                           [value: 22, color: "#8aae1e"],
                           [value: 23, color: "#b1a81f"],
                           [value: 24, color: "#b57d20"],
                           [value: 26, color: "#b85122"],
                           [value: 28, color: "#bc2323"]])
            }
            
            tileAttribute("device.nextHeatingSetpoint", key: "SECONDARY_CONTROL") 
            {
                attributeState("default", label:'${currentValue}° next', unit:"")
            }
        }
        
        controlTile("nextHeatingSetpointSlider", "device.nextHeatingSetpoint", "slider", height: 1, width: 6, inactiveLabel: false, range:"(4..28)" ) 
        {
            state "heatingSetpoint", action: "setHeatingSetpoint", backgroundColor:"#d04e00"
        }
        
        valueTile("batteryTile", "device.battery", inactiveLabel: true, decoration: "flat", width: 1, height: 1) 
        {
            tileAttribute ("device.battery", key: "PRIMARY_CONTROL")
            {
                state "default", label:'${currentValue}% battery', unit:"%"
            }
        }

        main "thermostatFull"
        details(["thermostatFull", "nextHeatingSetpointSlider", "batteryTile"])
    }
    
    preferences 
    {
        input "wakeUpInterval", "number", title: "Wake up interval", description: "Seconds until next wake up notification", range: "60..3600", displayDuringSetup: true
    }
}

def parse(String description) 
{
    def results = null
    
     def cmd = zwave.parse(description,[0x80: 1, 0x46: 1, 0x81: 1, 0x72: 2, 0x8F: 1, 0x75: 2, 0x43: 2, 0x86: 1, 0x84: 2])

    if (cmd != null &&
        (cmd instanceof physicalgraph.zwave.commands.batteryv1.BatteryReport ||
         cmd instanceof physicalgraph.zwave.commands.thermostatsetpointv2.ThermostatSetpointReport ||
         cmd instanceof physicalgraph.zwave.commands.wakeupv2.WakeUpNotification)) 
    {
        results = zwaveEvent(cmd)
    }
    
    return results
}

def zwaveEvent(physicalgraph.zwave.commands.batteryv1.BatteryReport cmd) 
{
        def eventList = []
        
        if (cmd.batteryLevel != (state.battery ?: -1))
        {  
            if (cmd.batteryLevel == 0xFF) 
            {  
                eventList << createEvent(descriptionText: "Device reports low battery", isStateChange: true)    
                eventList << createEvent(name:"battery", value: 1, unit: "%", displayed: false)
            } 
            else 
            {
                eventList << createEvent(descriptionText: "Device reports ${cmd.batteryLevel}% battery", isStateChange: true)    
                eventList << createEvent(name:"battery", value: cmd.batteryLevel, unit: "%", displayed: false)
                state.batery = cmd.batteryLevel
            }
           }

        state.lastbatt = new Date().time
        
         eventList
}

def zwaveEvent(physicalgraph.zwave.commands.thermostatsetpointv2.ThermostatSetpointReport cmd) 
{
    def eventList = []

    def value = convertTemperatureIfNeeded(cmd.scaledValue, (cmd.scale == 1 ? "F" : "C"), cmd.precision)

    value = Double.parseDouble(value).toString() - ".0"
    
    def descriptionText = "Device reports ${value}°"
    eventList << createEvent(name:"heatingSetpoint", value: value, unit: getTemperatureScale(), isStateChange: true, descriptionText: descriptionText)
    log.debug(descriptionText)
    
    state.heatingSetpoint = value;

    state.size = cmd.size
    state.scale = cmd.scale
    state.precision = cmd.precision
    
    eventList
}

def zwaveEvent(physicalgraph.zwave.commands.wakeupv2.WakeUpNotification cmd) 
{
    def eventList = []
  
     try 
    {
        def battery = (state.batery ?: "")
        
        if (battery == "")
        {
            log.debug("Requesting batery level")
            eventList << response(zwave.batteryV1.batteryGet())
            eventList << response("delay 1200")
        }

        def heatingSetpoint = (state.heatingSetpoint ?: "")
        def nextHeatingSetpoint = (state.nextHeatingSetpoint ?: "")

        if (nextHeatingSetpoint != "")
        {
            if (heatingSetpoint != nextHeatingSetpoint)
            {
                log.debug("Device is set to ${nextHeatingSetpoint}°")

                eventList << response(zwave.thermostatSetpointV2.thermostatSetpointSet(setpointType: 1, scale: 0, precision: 1, scaledValue: new BigDecimal(nextHeatingSetpoint)).format())
                eventList << response("delay 1200")
                eventList << response(zwave.thermostatSetpointV2.thermostatSetpointGet(setpointType: 1).format())
                eventList << response("delay 1200")
            }
            else
            {
                log.debug ("nextHeatingSetpoint is equal heatingSetpoint. No action taken")
            }
            
            state.nextHeatingSetpoint = ""
        }
        else if (heatingSetpoint == "")
        {
            log.debug("Requesting thermostat set point")
            eventList << response(zwave.thermostatSetpointV2.thermostatSetpointGet(setpointType: 1).format())
            eventList << response("delay 1200")
        }

        def interval = (wakeUpInterval ?: 60)

        if ((state.configured ?: "false") == "false" ||
            (state.currentWakeUpInterval ?: 0) != interval)
        {
            log.debug("Configuration is sent to device. Wake up Interval: ${interval} seconds")
            
            eventList << response(zwave.configurationV1.configurationSet(parameterNumber:1, size:2, scaledConfigurationValue:100).format())
            eventList << response("delay 1200")
            eventList << response(zwave.associationV1.associationSet(groupingIdentifier:1, nodeId:[zwaveHubNodeId]).format())
            eventList << response("delay 1200")
            eventList << response(zwave.wakeUpV1.wakeUpIntervalSet(seconds:interval, nodeid:zwaveHubNodeId).format())
            eventList << response("delay 1200")
            
            state.currentWakeUpInterval = interval
            state.configured = "true"
        }
    }
    catch (all)
    {
    }
    
    eventList << response(zwave.wakeUpV1.wakeUpNoMoreInformation())
   
    eventList
}

def setHeatingSetpoint(number) 
{
    def deviceScale = state.scale ?: 2
    def deviceScaleString = deviceScale == 2 ? "C" : "F"
    def locationScale = getTemperatureScale()
    def p = (state.precision ?: 1)

    Double convertedDegrees = number
    
    if (locationScale == "C" && 
        deviceScaleString == "F") 
    {
        convertedDegrees = celsiusToFahrenheit(degrees)
    } 
    else if (locationScale == "F" && 
             deviceScaleString == "C") 
    {
        convertedDegrees = fahrenheitToCelsius(degrees)
    } 
    
    def value = convertedDegrees.toString() - ".0"

    sendEvent(name:"nextHeatingSetpoint", value: value, displayed: false , isStateChange: true)
    log.debug ("Setting device to ${value}° on next wake up")
    
    state.nextHeatingSetpoint = value
}