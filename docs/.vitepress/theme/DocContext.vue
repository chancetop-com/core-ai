<script setup lang="ts">
import { computed } from 'vue';
import { useData, withBase } from 'vitepress';
const { page } = useData();
const english = computed(() => page.value.relativePath.startsWith('en/'));
</script>
<template>
  <aside v-if="page.frontmatter.historical" class="doc-context" role="note">
    <strong>{{ english ? 'Design / historical record' : '设计与历史记录' }}</strong>
    <p>{{ english ? 'This record may contain earlier interfaces or proposals. Confirm behavior against current use guides and source.' : '本页可能包含旧接口或设计提案。使用时以当前指南和源码为准，勿直接视为已发布功能。' }}</p>
    <a :href="withBase(english ? '/en/quickstart' : '/cn/quickstart')">{{ english ? 'Open current Quick Start →' : '查看当前快速开始 →' }}</a>
  </aside>
</template>
