import { fireEvent, render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';
import { RichCardView } from './CustomEventBlocks';
import type { RichCard } from '../types';

const card: RichCard = {
  schema_version: 1,
  title: 'Tell us about you',
  blocks: [
    {
      type: 'form',
      submit_label: 'Start',
      fields: [
        { name: 'name', label: 'Name', type: 'text', required: true },
        { name: 'seats', label: 'Seats', type: 'number' },
        { name: 'plan', label: 'Plan', type: 'select', options: ['Basic', 'Pro'] },
        { name: 'notes', label: 'Notes', type: 'textarea' },
        { name: 'newsletter', label: 'Newsletter', type: 'checkbox' },
      ],
    },
  ],
};

function renderCard(onAction = vi.fn(), disabled = false) {
  render(<RichCardView card={card} disabled={disabled} onAction={onAction} />);
  return onAction;
}

const typedCard: RichCard = {
  schema_version: 1,
  blocks: [
    {
      type: 'form',
      submit_label: 'Send',
      fields: [
        {
          name: 'plan',
          label: 'Plan',
          type: 'select',
          options: [{ label: '基础版', value: 'basic' }, { label: '专业版', value: 'pro' }],
        },
        { name: 'channels', label: 'Channels', type: 'checkboxes', options: ['Email', 'SMS'] },
        { name: 'slot', label: 'Slot', type: 'radio', options: ['Morning', 'Afternoon'] },
        { name: 'topics', label: 'Topics', type: 'multiselect', options: ['Billing', 'Support'] },
        { name: 'contact', label: 'Contact email', type: 'email', required: true },
        { name: 'start', label: 'Start date', type: 'date' },
        { name: 'seats', label: 'Seats', type: 'number', min: 1, max: 5 },
      ],
    },
  ],
};

function renderTypedCard(onAction = vi.fn()) {
  render(<RichCardView card={typedCard} disabled={false} onAction={onAction} />);
  return onAction;
}

describe('RichCardView form block', () => {
  it('submits the collected values as a JSON user message', async () => {
    const onAction = renderCard();

    await userEvent.type(screen.getByLabelText(/^Name/), 'Ada');
    await userEvent.type(screen.getByLabelText(/^Seats/), '12');
    await userEvent.selectOptions(screen.getByLabelText(/^Plan/), 'Pro');
    await userEvent.type(screen.getByLabelText(/^Notes/), 'hello');
    await userEvent.click(screen.getByLabelText(/^Newsletter/));
    await userEvent.click(screen.getByRole('button', { name: 'Start' }));

    expect(onAction).toHaveBeenCalledWith(
      JSON.stringify({ name: 'Ada', seats: 12, plan: 'Pro', notes: 'hello', newsletter: true }),
    );
  });

  it('omits blank optional fields and keeps unchecked boxes as false', async () => {
    const onAction = renderCard();

    await userEvent.type(screen.getByLabelText(/^Name/), 'Ada');
    await userEvent.click(screen.getByRole('button', { name: 'Start' }));

    expect(onAction).toHaveBeenCalledWith(JSON.stringify({ name: 'Ada', newsletter: false }));
  });

  it('blocks submit while a required field is empty', async () => {
    const onAction = renderCard();

    await userEvent.click(screen.getByRole('button', { name: 'Start' }));

    expect(onAction).not.toHaveBeenCalled();
    expect(screen.getByText('Required')).toBeTruthy();
  });

  it('does not submit while the turn is running', async () => {
    const onAction = renderCard(vi.fn(), true);

    await userEvent.click(screen.getByRole('button', { name: 'Start' }));

    expect(onAction).not.toHaveBeenCalled();
  });

  it('collects every field type with the option value, not its label', async () => {
    const onAction = renderTypedCard();

    await userEvent.selectOptions(screen.getByLabelText(/^Plan/), 'pro');
    await userEvent.click(screen.getByLabelText('Email'));
    await userEvent.click(screen.getByLabelText('SMS'));
    await userEvent.click(screen.getByLabelText('Afternoon'));
    await userEvent.selectOptions(screen.getByLabelText(/^Topics/), ['Billing', 'Support']);
    await userEvent.type(screen.getByLabelText(/^Contact email/), 'ada@example.com');
    fireEvent.change(screen.getByLabelText(/^Start date/), { target: { value: '2026-11-05' } });
    await userEvent.type(screen.getByLabelText(/^Seats/), '3');
    await userEvent.click(screen.getByRole('button', { name: 'Send' }));

    expect(onAction).toHaveBeenCalledWith(JSON.stringify({
      plan: 'pro',
      channels: ['Email', 'SMS'],
      slot: 'Afternoon',
      topics: ['Billing', 'Support'],
      contact: 'ada@example.com',
      start: '2026-11-05',
      seats: 3,
    }));
  });

  it('omits untouched optional choice groups', async () => {
    const onAction = renderTypedCard();

    await userEvent.selectOptions(screen.getByLabelText(/^Plan/), 'basic');
    await userEvent.click(screen.getByLabelText('Morning'));
    await userEvent.type(screen.getByLabelText(/^Contact email/), 'ada@example.com');
    fireEvent.change(screen.getByLabelText(/^Start date/), { target: { value: '2026-11-05' } });
    await userEvent.type(screen.getByLabelText(/^Seats/), '2');
    await userEvent.click(screen.getByRole('button', { name: 'Send' }));

    expect(onAction).toHaveBeenCalledWith(JSON.stringify({
      plan: 'basic',
      slot: 'Morning',
      contact: 'ada@example.com',
      start: '2026-11-05',
      seats: 2,
    }));
  });

  it('rejects an invalid email and an out-of-range number', async () => {
    const onAction = renderTypedCard();

    await userEvent.type(screen.getByLabelText(/^Contact email/), 'not-an-email');
    await userEvent.type(screen.getByLabelText(/^Seats/), '9');
    await userEvent.click(screen.getByRole('button', { name: 'Send' }));

    expect(onAction).not.toHaveBeenCalled();
    expect(screen.getByText('Enter a valid email')).toBeTruthy();
    expect(screen.getByText('Must be at most 5')).toBeTruthy();
  });
});
