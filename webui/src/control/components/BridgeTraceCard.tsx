import type { BridgeClient, BridgeTraceEvent } from '@pochita/bridge';
import { BridgeTraceInspector } from '@pochita/ui';
import { useEffect, useState } from 'react';

export interface BridgeTraceCardProps {
  bridge: BridgeClient;
}

export function BridgeTraceCard({ bridge }: BridgeTraceCardProps) {
  const [entries, setEntries] = useState<BridgeTraceEvent[]>(() => bridge.getTraceEntries());

  useEffect(() => {
    setEntries(bridge.getTraceEntries());
    return bridge.subscribeTrace(() => {
      setEntries(bridge.getTraceEntries());
    });
  }, [bridge]);

  return (
    <BridgeTraceInspector
      title="Bridge 通信追踪"
      entries={entries}
      onClear={() => {
        bridge.clearTraceEntries();
        setEntries([]);
      }}
      maxHeightClassName="max-h-64"
    />
  );
}
