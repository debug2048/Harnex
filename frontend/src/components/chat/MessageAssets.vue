<script setup lang="ts">
import { computed } from 'vue'
import type { ContentPart } from '../../../../packages/protocol/workbench'
import ArtifactCard from './ArtifactCard.vue'
import ImageOperationCard from './ImageOperationCard.vue'
const props = defineProps<{ parts?: ContentPart[] }>()
const artifacts = computed(() => [...new Set((props.parts || []).filter(part => part.type === 'ARTIFACT_REF')
  .map(part => part.artifactId).filter((id): id is string => !!id))])
const operations = computed(() => [...new Set((props.parts || []).filter(part => part.type === 'IMAGE_OPERATION_REF')
  .map(part => part.imageOperationId).filter((id): id is string => !!id))])
</script>
<template>
  <div v-if="artifacts.length || operations.length" class="message-assets">
    <ImageOperationCard v-for="id in operations" :key="id" :operation-id="id" :excluded-artifact-ids="artifacts" />
    <ArtifactCard v-for="id in artifacts" :key="id" :artifact-id="id" />
  </div>
</template>
