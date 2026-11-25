import assert from 'node:assert/strict';
import test from 'node:test';
import React from 'react';
import { renderToStaticMarkup } from 'react-dom/server';
import { createServer } from 'vite';
import { parseNetworkExecOutput } from '../src/control/hooks/useNetworkContextControl.ts';
import { handleMockControlExec } from '../src/control/mockControlBridge.ts';
import {
  appendWifiSsid,
  buildCreateWebEmbedInput,
  buildPresetAdvancedValues,
  cleanWifiSsid,
  DEFAULT_WEB_EMBED_ADVANCED,
  EMBED_PRESETS,
  extractConnectedWifiSsid,
  isValidCreateWebEmbedDraft,
  resolveWifiSsidDetection,
  resolveWifiSsidFill,
  WIFI_SSID_UNAVAILABLE_HINT,
} from '../src/control/webEmbedHelpers.ts';

test('EMBED_PRESETS keeps only Home Assistant while custom manual embed drafts stay supported', () => {
  assert.equal(EMBED_PRESETS.length, 1);
  assert.equal(EMBED_PRESETS[0]?.id, 'lan.homeassistant');
  assert.equal(EMBED_PRESETS[0]?.label, 'Home Assistant');

  for (const removed of ['OpenClash', 'LuCI', 'Zashboard']) {
    assert.equal(
      EMBED_PRESETS.some(
        (preset) =>
          preset.label.includes(removed) ||
          preset.name.includes(removed) ||
          preset.id.toLowerCase().includes(removed.toLowerCase()),
      ),
      false,
      `Preset ${removed} should have been removed`,
    );
  }

  assert.equal(
    isValidCreateWebEmbedDraft(
      'custom.metrics',
      'Custom Panel',
      'https://panel.example.com/overview',
      'https://ha.example.com:8123',
    ),
    true,
  );
  assert.equal(
    isValidCreateWebEmbedDraft('', 'Custom Panel', 'https://panel.example.com', ''),
    false,
  );
  assert.equal(
    isValidCreateWebEmbedDraft('custom.metrics', 'Custom Panel', 'ftp://bad', ''),
    false,
  );
  assert.equal(
    isValidCreateWebEmbedDraft(
      'custom.metrics',
      'Custom Panel',
      'https://panel.example.com',
      'bad',
    ),
    false,
  );

  const payload = buildCreateWebEmbedInput({
    id: ' custom.metrics ',
    name: ' Custom Panel ',
    url: ' http://192.168.1.50:3000/dashboard ',
    originsText: 'http://192.168.1.50:3000, https://panel.example.com',
    allowCleartext: true,
    keepAlive: false,
    globalScope: true,
    priority: 7,
    advanced: {
      ...DEFAULT_WEB_EMBED_ADVANCED,
      remoteUrl: ' https://panel.example.com ',
      lanSsidsText: 'HomeRouter-5G, Office-5G',
      widthDp: 420,
      heightPercent: 80,
      textZoom: 105,
    },
  });
  assert.equal(payload.id, 'custom.metrics');
  assert.equal(payload.name, 'Custom Panel');
  assert.equal(payload.url, 'http://192.168.1.50:3000/dashboard');
  assert.equal(payload.remoteUrl, 'https://panel.example.com');
  assert.deepEqual(payload.lanSsids, ['HomeRouter-5G', 'Office-5G']);
  assert.deepEqual(payload.allowedOrigins, [
    'http://192.168.1.50:3000',
    'https://panel.example.com',
  ]);
  assert.equal(payload.widthDp, 420);
  assert.equal(payload.heightPercent, 80);
  assert.equal(payload.textZoom, 105);
});

test('extractConnectedWifiSsid and cleanWifiSsid sanitize Wi-Fi snapshots and ignore non-Wi-Fi/unknown', async () => {
  assert.equal(cleanWifiSsid(' "HomeRouter-5G" '), 'HomeRouter-5G');
  assert.equal(cleanWifiSsid(" 'Office-5G' "), 'Office-5G');
  assert.equal(cleanWifiSsid(''), null);
  assert.equal(cleanWifiSsid('""'), null);
  assert.equal(cleanWifiSsid('<unknown ssid>'), null);
  assert.equal(cleanWifiSsid('"<UNKNOWN SSID>"'), null);
  assert.equal(cleanWifiSsid(null), null);

  assert.equal(
    extractConnectedWifiSsid({
      transport: 'wifi',
      vpnActive: false,
      validated: true,
      metered: false,
      ssid: '"HomeRouter-5G"',
    }),
    'HomeRouter-5G',
  );
  assert.equal(
    extractConnectedWifiSsid({
      transport: 'wifi',
      vpnActive: false,
      validated: true,
      metered: false,
      ssid: '<unknown ssid>',
    }),
    null,
  );
  assert.equal(
    extractConnectedWifiSsid({
      transport: 'cellular',
      vpnActive: false,
      validated: true,
      metered: true,
      ssid: 'HomeRouter-5G',
    }),
    null,
  );
  assert.equal(extractConnectedWifiSsid(null), null);

  const mockRes = await handleMockControlExec('sideveil-ctl network');
  assert.equal(mockRes.errno, 0);
  const parsed = parseNetworkExecOutput(mockRes.stdout);
  assert.equal(extractConnectedWifiSsid(parsed), 'HomeRouter-5G');
});

test('appendWifiSsid and resolveWifiSsidDetection deduplicate entries and return inline hint when unavailable', async () => {
  assert.equal(appendWifiSsid('', 'HomeRouter-5G'), 'HomeRouter-5G');
  assert.equal(appendWifiSsid('HomeRouter-5G', 'Office-5G'), 'HomeRouter-5G, Office-5G');
  assert.equal(
    appendWifiSsid('HomeRouter-5G, Office-5G', '"HomeRouter-5G"'),
    'HomeRouter-5G, Office-5G',
  );
  assert.equal(appendWifiSsid(' "Office-5G" , ', 'HomeRouter-5G'), 'Office-5G, HomeRouter-5G');
  assert.equal(appendWifiSsid('Office-5G', '<unknown ssid>'), 'Office-5G');

  const filled = resolveWifiSsidFill('Office-5G', '"HomeRouter-5G"');
  assert.deepEqual(filled, {
    nextText: 'Office-5G, HomeRouter-5G',
    filledSsid: 'HomeRouter-5G',
    hint: null,
  });

  const unavailable = resolveWifiSsidFill('Office-5G', '<unknown ssid>');
  assert.deepEqual(unavailable, {
    nextText: 'Office-5G',
    filledSsid: null,
    hint: WIFI_SSID_UNAVAILABLE_HINT,
  });

  let detectCalls = 0;
  const detectedRes = await resolveWifiSsidDetection('Office-5G', null, async () => {
    detectCalls += 1;
    return '"HomeRouter-5G"';
  });
  assert.equal(detectCalls, 1);
  assert.equal(detectedRes.nextText, 'Office-5G, HomeRouter-5G');
  assert.equal(detectedRes.filledSsid, 'HomeRouter-5G');
  assert.equal(detectedRes.hint, null);

  const dedupedRes = await resolveWifiSsidDetection(
    detectedRes.nextText,
    'HomeRouter-5G',
    async () => 'HomeRouter-5G',
  );
  assert.equal(dedupedRes.nextText, 'Office-5G, HomeRouter-5G');
  assert.equal(dedupedRes.hint, null);

  const disconnectedRes = await resolveWifiSsidDetection(
    'Office-5G',
    'HomeRouter-5G',
    async () => null,
  );
  assert.equal(disconnectedRes.nextText, 'Office-5G');
  assert.equal(disconnectedRes.filledSsid, null);
  assert.equal(disconnectedRes.hint, WIFI_SSID_UNAVAILABLE_HINT);

  const failedRes = await resolveWifiSsidDetection('', null, async () => {
    throw new Error('network ctl failed');
  });
  assert.equal(failedRes.nextText, '');
  assert.equal(failedRes.filledSsid, null);
  assert.equal(failedRes.hint, WIFI_SSID_UNAVAILABLE_HINT);
});

test('buildPresetAdvancedValues auto-fills current Wi-Fi SSID on HA preset without clobbering user input', () => {
  const haPreset = EMBED_PRESETS[0];
  assert.ok(haPreset);

  const prefilled = buildPresetAdvancedValues(haPreset, '', '"HomeRouter-5G"');
  assert.equal(prefilled.lanSsidsText, 'HomeRouter-5G');
  assert.equal(prefilled.widthDp, 400);
  assert.equal(prefilled.textZoom, 95);

  const preserved = buildPresetAdvancedValues(haPreset, 'Office-5G', 'HomeRouter-5G');
  assert.equal(preserved.lanSsidsText, 'Office-5G');

  const withoutWifi = buildPresetAdvancedValues(haPreset, '', null);
  assert.equal(withoutWifi.lanSsidsText, '');
});

test('CreateWebEmbedCard, PluginEmbedDialog, WebEmbedAdvancedFields, and PluginCategoryContent render expected presets and Wi-Fi actions', async () => {
  const server = await createServer({
    configFile: false,
    logLevel: 'silent',
    optimizeDeps: { noDiscovery: true },
    server: { middlewareMode: true },
    appType: 'custom',
  });

  try {
    const [createMod, dialogMod, advancedMod, categoryMod] = await Promise.all([
      server.ssrLoadModule('/src/control/components/CreateWebEmbedCard.tsx'),
      server.ssrLoadModule('/src/control/components/PluginEmbedDialog.tsx'),
      server.ssrLoadModule('/src/control/components/WebEmbedAdvancedFields.tsx'),
      server.ssrLoadModule('/src/control/components/PluginCategoryContent.tsx'),
    ]);

    const createHtml = renderToStaticMarkup(
      React.createElement(createMod.CreateWebEmbedCard, {
        isBusy: false,
        currentWifiSsid: 'HomeRouter-5G',
        onDetectWifiSsid: async () => 'HomeRouter-5G',
        onCreate: async () => true,
        onCancel: () => {},
      }),
    );
    assert.ok(createHtml.includes('aria-label="填充 Home Assistant 模板"'));
    assert.equal(createHtml.includes('OpenClash'), false);
    assert.equal(createHtml.includes('LuCI'), false);
    assert.equal(createHtml.includes('Zashboard'), false);
    assert.ok(createHtml.includes('aria-label="网页嵌入插件 ID"'));
    assert.ok(createHtml.includes('aria-label="网页嵌入显示名称"'));
    assert.ok(createHtml.includes('aria-label="网页嵌入目标 URL"'));
    assert.ok(createHtml.includes('aria-label="网页嵌入允许源列表"'));
    assert.ok(createHtml.includes('aria-label="网页嵌入优先级"'));

    const emptyHtml = renderToStaticMarkup(
      React.createElement(categoryMod.PluginCategoryContent, {
        category: 'web-embed',
        grouped: {
          webEmbeds: [],
          installed: [],
          builtins: [],
          systemBuiltins: [],
          bundledExamples: [],
        },
        renderCard: () => null,
        onStartCreateEmbed: () => {},
        onStartImport: () => {},
      }),
    );
    assert.ok(emptyHtml.includes('Home Assistant'));
    assert.equal(emptyHtml.includes('OpenClash'), false);
    assert.equal(emptyHtml.includes('LuCI'), false);
    assert.equal(emptyHtml.includes('Zashboard'), false);

    const dialogHtml = renderToStaticMarkup(
      React.createElement(dialogMod.PluginEmbedDialog, {
        pluginId: 'lan.homeassistant',
        pluginName: 'Home Assistant',
        embed: {
          url: 'http://homeassistant.local:8123',
          remoteUrl: 'https://ha.example.com:8123',
          lanSsids: ['Office-5G'],
          allowedOrigins: ['http://homeassistant.local:8123'],
          allowCleartext: true,
        },
        initialKeepAlive: true,
        isBusy: false,
        currentWifiSsid: 'HomeRouter-5G',
        onDetectWifiSsid: async () => 'HomeRouter-5G',
        onSave: async () => true,
        onCancel: () => {},
      }),
    );
    assert.ok(dialogHtml.includes('aria-label="Home Assistant 填入当前 Wi-Fi SSID"'));
    assert.ok(dialogHtml.includes('填入当前 Wi-Fi · HomeRouter-5G'));
    assert.ok(dialogHtml.includes('value="Office-5G"'));

    const loadingAdvancedHtml = renderToStaticMarkup(
      React.createElement(advancedMod.WebEmbedAdvancedFields, {
        labelPrefix: '新建网页嵌入',
        values: DEFAULT_WEB_EMBED_ADVANCED,
        keepAlive: true,
        isBusy: false,
        expanded: true,
        currentWifiSsid: null,
        networkLoading: true,
        onDetectWifiSsid: async () => null,
        onToggleExpanded: () => {},
        onChange: () => {},
      }),
    );
    assert.ok(loadingAdvancedHtml.includes('aria-label="新建网页嵌入填入当前 Wi-Fi SSID"'));
    assert.ok(loadingAdvancedHtml.includes('检测中...'));
  } finally {
    await server.close();
  }
});
