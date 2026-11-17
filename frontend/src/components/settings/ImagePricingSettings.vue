<script setup lang="ts">
import ConfigField from './ConfigField.vue'
import { computed, ref } from 'vue'
import { useConfigStore } from '../../stores/config'
import { imageModelLabel, imageSettingsMap } from '../../domain/imageSettings'
import { isRecord, type UnknownRecord } from '../../utils/unknown'
import { t } from '../../i18n'
defineProps<{ writable: boolean }>()
const config = useConfigStore()
const services = computed(() => imageSettingsMap(Object.prototype.hasOwnProperty.call(config.draft, 'image-services')
  ? config.draft['image-services'] : config.effective.imageServices))
const price = (value: UnknownRecord): UnknownRecord => isRecord(value.pricing) ? value.pricing : {}
const priceModes = ref<Record<string, string>>({})
const mode = (id: string, value: UnknownRecord) => !value.pricing ? '' : priceModes.value[id] || (price(value)['per-image'] != null ? 'image' : 'tokens')
function update(id: string, pricing?: UnknownRecord) {
  const service = { ...services.value[id] }
  if (pricing) service.pricing = pricing; else delete service.pricing
  config.setSection('image-services', { ...services.value, [id]: service })
}
function selectMode(id: string, next: string) {
  priceModes.value[id] = next
  update(id, next ? { currency: price(services.value[id]).currency || 'USD' } : undefined)
}
function field(id: string, key: string, value: string) {
  const pricing = { ...price(services.value[id]) }
  if (value === '') delete pricing[key]
  else pricing[key] = key === 'currency' ? value.toUpperCase() : Number(value)
  update(id, pricing)
}
const text = (event: Event) => (event.target as HTMLInputElement).value
</script>
<template>
  <div class="image-pricing">
    <p>{{ t('imageSettings.pricingOptional') }}</p>
    <p v-if="!Object.keys(services).length">{{ t('imageSettings.pricingEmpty') }}</p>
    <fieldset v-for="(service, id) in services" :key="id" :data-service-id="id" :disabled="!writable">
      <legend>{{ imageModelLabel(service) || t('imageSettings.unnamed') }}</legend>
      <ConfigField :path="`image-services.${id}.pricing`" v-slot="{ attrs }">{{ t('imageSettings.pricingBasis') }}<select v-bind="attrs" :value="mode(id, service)" @change="selectMode(id, text($event))">
        <option value="">{{ t('imageSettings.priceUnknown') }}</option><option value="image">{{ t('imageSettings.perImage') }}</option><option value="tokens">{{ t('imageSettings.perMillion') }}</option>
      </select></ConfigField>
      <div v-if="service.pricing" class="price-fields">
        <ConfigField :path="`image-services.${id}.pricing.currency`" v-slot="{ attrs }">{{ t('imageSettings.currency') }}<input v-bind="attrs" :value="price(service).currency" maxlength="3" @input="field(id, 'currency', text($event))" /></ConfigField>
        <ConfigField :path="`image-services.${id}.pricing.${key}`" v-slot="{ attrs }" v-for="key in mode(id, service) === 'image' ? ['per-image'] : ['text-input-per-million', 'image-input-per-million', 'image-output-per-million']" :key="key">{{ t(`imageSettings.${key}`) }}<input v-bind="attrs" type="number" min="0" step="any" :value="price(service)[key]" @input="field(id, key, text($event))" /></ConfigField>
      </div>
    </fieldset>
  </div>
</template>
<style scoped>
.image-pricing { display:grid; gap:18px; } p { color:var(--text-muted); line-height:1.6; font-size:13px; margin:0; }
fieldset { border:1px solid var(--border); border-radius:10px; padding:16px; min-width:0; } legend { padding:0 6px; color:var(--text-primary); }
.price-fields { display:grid; grid-template-columns:repeat(2,minmax(0,1fr)); gap:12px; margin-top:12px; }
label { display:grid; gap:6px; color:var(--text-secondary); font-size:13px; }
input,select { width:100%; min-width:0; padding:8px; border:1px solid var(--border); border-radius:6px; background:var(--surface-1); color:var(--text-primary); }
@media(max-width:600px) { .price-fields { grid-template-columns:1fr; } }
</style>
