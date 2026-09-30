<script setup lang="ts">
import DefaultTheme from 'vitepress/theme';
import LanguageSwitch from './LanguageSwitch.vue';
import DocContext from './DocContext.vue';
import ImagePreview from './ImagePreview.vue';
</script>
<template>
  <DefaultTheme.Layout>
    <template #nav-bar-content-before><LanguageSwitch /></template>
    <template #doc-before><DocContext /></template>
    <template #layout-bottom><ImagePreview /></template>
  </DefaultTheme.Layout>
</template>
