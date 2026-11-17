import type { ContextPlan, UsageEntry } from '../../../packages/protocol/workbench'

export function summarizeModelUsage(entries: UsageEntry[]) {
  const groups = new Map<string, { key: string; provider: string; model: string; count: number;
    input: number; output: number; images: number; knownAmounts: Record<string, number>; complete: boolean }>()
  for (const entry of entries) {
    const key = JSON.stringify([entry.provider, entry.model])
    const group = groups.get(key) || { key, provider: entry.provider, model: entry.model, count: 0,
      input: 0, output: 0, images: 0, knownAmounts: {}, complete: true }
    group.count++
    group.input += entry.quantities[entry.kind === 'TEXT' ? 'prompt' : 'input_tokens'] || 0
    group.output += entry.quantities[entry.kind === 'TEXT' ? 'completion' : 'output_tokens'] || 0
    group.images += entry.quantities.images || 0
    group.complete &&= entry.costComplete
    const currency = entry.price?.currency
    if (currency) group.knownAmounts[currency] = (group.knownAmounts[currency] || 0) + entry.knownAmount
    groups.set(key, group)
  }
  return [...groups.values()]
}

export function summarizeContextBlocks(blocks: ContextPlan['blocks']) {
  const groups = new Map<string, { kind: string; included: number; omitted: number; tokens: number }>()
  for (const block of blocks) {
    const group = groups.get(block.sourceKind) || { kind: block.sourceKind, included: 0, omitted: 0, tokens: 0 }
    if (block.included) { group.included++; group.tokens += block.estimatedTokens }
    else group.omitted++
    groups.set(block.sourceKind, group)
  }
  return [...groups.values()]
}
