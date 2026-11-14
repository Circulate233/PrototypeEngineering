package com.circulation.prototype_eng.api.network;

import com.circulation.prototype_eng.api.machine.MachineInstance;
import com.circulation.prototype_eng.api.machine.event.MachineEvent;

/**
 * 在服务器所属线程修改本设备的候选数据。回调可重复计算，禁止修改其他设备或直接扣费。
 */
public record NetworkDeviceEvent(MachineInstance machine, NetworkData network,
                                 NetworkResourceState values) implements MachineEvent {
}
