import chalk from 'chalk';

export const theme = {
  prompt: chalk.hex('#72D687'),
  separator: chalk.hex('#5F87AF'),
  error: chalk.hex('#FF5F5F'),
  warning: chalk.hex('#FFAF00'),
  muted: chalk.hex('#8A8A8A'),
  success: chalk.hex('#72D687'),
  cmdName: chalk.bold.hex('#5FAFFF'),
  cmdDesc: chalk.hex('#DADADA'),

  mdHeader: chalk.bold.hex('#5FAFFF'),
  mdInlineCode: chalk.hex('#FFAF87'),
  mdCodeBlock: chalk.hex('#DADADA'),
  mdBold: chalk.bold,
  mdItalic: chalk.italic,
  mdBullet: chalk.hex('#5F87AF'),
  mdTableBorder: chalk.hex('#585858'),

  reasoning: chalk.dim,

  synKeyword: chalk.hex('#D787FF'),
  synString: chalk.hex('#AFD75F'),
  synComment: chalk.hex('#808080'),
  synNumber: chalk.hex('#FFAF87'),
  synType: chalk.hex('#5FD7FF'),
  synAnnotation: chalk.hex('#FFAF00'),
  synDiffAdd: chalk.hex('#72D687'),
  synDiffDel: chalk.hex('#FF5F5F'),
};

export const icons = {
  prompt: '❯',
  bullet: '⏺',
  toolStart: '⏺',
  toolResult: '⏿',
  success: '✓',
  error: '✗',
  warning: '!',
  spinner: ['⠋', '⠙', '⠹', '⠸', '⠼', '⠴', '⠦', '⠧', '⠇', '⠏'],
};
