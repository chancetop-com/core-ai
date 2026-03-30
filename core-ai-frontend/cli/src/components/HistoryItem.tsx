import React from 'react';
import type { HistoryItem } from '../types.js';
import { UserMessage } from './messages/UserMessage.js';
import { AssistantMessage } from './messages/AssistantMessage.js';
import { ToolStartMessage, ToolResultMessage } from './messages/ToolMessage.js';
import { ThinkingMessage } from './messages/ThinkingMessage.js';
import { InfoMessage, WarningMessage, ErrorMessage, SystemMessage } from './messages/InfoMessage.js';

interface HistoryItemDisplayProps {
  item: HistoryItem;
}

export const HistoryItemDisplay: React.FC<HistoryItemDisplayProps> = ({ item }) => {
  switch (item.type) {
    case 'user':
      return <UserMessage content={item.content} />;
    case 'assistant':
      return <AssistantMessage content={item.content} isStreaming={item.isStreaming} />;
    case 'thinking':
      return <ThinkingMessage content={item.content} isStreaming={item.isStreaming} />;
    case 'tool_start':
      return <ToolStartMessage toolName={item.toolName || ''} toolArgs={item.toolArgs} />;
    case 'tool_result':
      return <ToolResultMessage toolName={item.toolName || ''} content={item.content} resultStatus={item.resultStatus} />;
    case 'warning':
      return <WarningMessage content={item.content} />;
    case 'error':
      return <ErrorMessage content={item.content} />;
    case 'info':
    case 'help':
    case 'stats':
    case 'tools_list':
    case 'mcp_status':
      return <InfoMessage content={item.content} />;
    case 'system':
      return <SystemMessage content={item.content} />;
    default:
      return <InfoMessage content={item.content} />;
  }
};
