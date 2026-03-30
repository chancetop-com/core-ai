import React, { useMemo } from 'react';
import { Box, Text, Static } from 'ink';
import type { HistoryItem } from '../types.js';
import { HistoryItemDisplay } from './HistoryItem.js';
import { Banner } from './Banner.js';
import { useUIState } from '../contexts/UIStateContext.js';
import { useConfig } from '../contexts/ConfigContext.js';

interface StaticItem {
  id: string;
  kind: 'banner' | 'history';
  historyItem?: HistoryItem;
}

export const MainContent: React.FC<{ version: string }> = ({ version }) => {
  const { history, pendingItems } = useUIState();
  const config = useConfig();

  // build static items: banner first, then history
  const staticItems = useMemo<StaticItem[]>(() => {
    const items: StaticItem[] = [
      { id: '__banner__', kind: 'banner' },
    ];
    for (const h of history) {
      items.push({ id: h.id, kind: 'history', historyItem: h });
    }
    return items;
  }, [history]);

  return (
    <Box flexDirection="column">
      <Static items={staticItems}>
        {(item: StaticItem) => {
          if (item.kind === 'banner') {
            return (
              <Box key={item.id}>
                <Banner
                  agentName={config.agentName}
                  modelName={config.modelName}
                  version={version}
                />
              </Box>
            );
          }
          return (
            <Box key={item.id} marginLeft={2} marginRight={2} flexDirection="column">
              <HistoryItemDisplay item={item.historyItem!} />
            </Box>
          );
        }}
      </Static>

      {pendingItems.length > 0 && (
        <Box flexDirection="column">
          {pendingItems.map(item => (
            <Box key={item.id} marginLeft={2} marginRight={2} flexDirection="column">
              <HistoryItemDisplay item={item} />
            </Box>
          ))}
        </Box>
      )}
    </Box>
  );
};
