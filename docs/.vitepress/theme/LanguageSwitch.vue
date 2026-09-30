<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';
import { useData, withBase } from 'vitepress';
const { page, hash } = useData();
const mounted = ref(false);
onMounted(() => { mounted.value = true; });
const english = computed(() => page.value.relativePath.startsWith('en/'));
const alternate = computed(() => page.value.frontmatter.alternate);
const href = computed(() => {
  const target = alternate.value;
  let fragment = '';
  try {
    if (mounted.value && target?.translated && target.ids.includes(decodeURIComponent(hash.value.slice(1)))) fragment = hash.value;
  } catch {}
  return withBase((target?.href || (english.value ? '/cn/' : '/en/')) + fragment);
});
const label = computed(() => (english.value ? '简体中文' : 'English') + (alternate.value?.translated ? '' : ' · Home'));
const title = computed(() => alternate.value?.translated
  ? (english.value ? 'Read this page in Chinese' : '切换到本页英文版本')
  : (english.value ? 'This page has no Chinese counterpart; open the Chinese home' : '此页暂无英文对应内容，打开英文首页'));
</script>

<template><a class="doc-language-switch" :href="href" :title="title">{{ label }}</a></template>
