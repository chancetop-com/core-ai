<script setup lang="ts">
import { nextTick, onMounted, ref } from 'vue';
import { onContentUpdated, useData } from 'vitepress';
const { page } = useData();
const dialog = ref<HTMLDialogElement>();
const src = ref('');
const description = ref('');
const wired = new WeakSet<HTMLImageElement>();
let trigger: HTMLImageElement | undefined;
async function open(image: HTMLImageElement) {
  trigger = image; src.value = image.currentSrc || image.src; description.value = image.alt;
  await nextTick(); dialog.value?.showModal();
}
function close() { dialog.value?.close(); }
function restoreFocus() { trigger?.focus(); }
function wire() {
  document.querySelectorAll<HTMLImageElement>('.vp-doc img').forEach(image => {
    if (wired.has(image) || image.closest('a')) return;
    wired.add(image); image.tabIndex = 0; image.setAttribute('role', 'button');
    image.setAttribute('aria-haspopup', 'dialog');
    image.setAttribute('aria-label', (page.value.relativePath.startsWith('en/') ? 'Enlarge image: ' : '放大图片：') + image.alt);
    image.setAttribute('data-doc-preview', '');
    image.addEventListener('click', () => open(image));
    image.addEventListener('keydown', event => {
      if (event.key === 'Enter' || event.key === ' ') { event.preventDefault(); open(image); }
    });
  });
}
onMounted(wire);
onContentUpdated(() => nextTick(wire));
</script>
<template>
  <dialog ref="dialog" class="doc-image-dialog" aria-labelledby="image-preview-title" @close="restoreFocus" @click="($event.target === $event.currentTarget) && close()">
    <div class="preview-heading">
      <strong id="image-preview-title">{{ description || 'Screenshot' }}</strong>
      <button type="button" @click="close">{{ page.relativePath.startsWith('en/') ? 'Close' : '关闭' }} ×</button>
    </div>
    <img v-if="src" :src="src" :alt="description">
    <p>{{ page.relativePath.startsWith('en/') ? 'Press Esc to close. Open the original for full-size detail.' : '按 Esc 关闭。需要查看细节时可打开原图。' }} <a :href="src" target="_blank" rel="noopener">{{ page.relativePath.startsWith('en/') ? 'Original image' : '打开原图' }}</a></p>
  </dialog>
</template>
