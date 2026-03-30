import React from 'react';
import { Box, Text } from 'ink';
import { theme, icons } from '../utils/theme.js';
import type { ApprovalRequest } from '../types.js';
import { formatToolSummary } from '../utils/toolSummary.js';

interface ToolApprovalProps {
  request: ApprovalRequest;
  onDecision: (decision: 'approve' | 'deny') => void;
}

export const ToolApproval: React.FC<ToolApprovalProps> = ({ request, onDecision }) => {
  return (
    <Box flexDirection="column" marginLeft={2}>
      <Text>
        {theme.warning(icons.warning)} Tool approval required: {theme.mdBold(request.tool)}
      </Text>
      {request.args && (
        <Text>  {theme.muted(formatToolSummary(request.tool, request.args))}</Text>
      )}
      <Text>
        {'  '}{theme.success('[y]')} Approve  {theme.error('[n]')} Deny
      </Text>
    </Box>
  );
};
