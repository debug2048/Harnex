<script setup lang="ts">
import brandIcon from '../assets/harnex-symbol.svg'

const symbolStyle = { '--brand-symbol': `url("${brandIcon}")` }

withDefaults(defineProps<{
  size?: 'sm' | 'md' | 'lg'
  label?: string
}>(), {
  size: 'sm',
  label: 'Harnex',
})
</script>

<template>
  <div class="brand-logo" :class="`brand-logo--${size}`" :style="symbolStyle" role="img" :aria-label="label">
    <span class="brand-logo__symbol" aria-hidden="true" />
  </div>
</template>

<style scoped>
.brand-logo {
  flex-shrink: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  color: var(--text-primary);
}
.brand-logo--sm {
  width: 32px;
  height: 32px;
}
.brand-logo--md {
  width: 44px;
  height: 44px;
}
.brand-logo--lg {
  width: 48px;
  height: 48px;
}
.brand-logo__symbol {
  display: block;
  width: 100%;
  height: 100%;
  background: currentColor;
  -webkit-mask: var(--brand-symbol) center / contain no-repeat;
  mask: var(--brand-symbol) center / contain no-repeat;
}
</style>
