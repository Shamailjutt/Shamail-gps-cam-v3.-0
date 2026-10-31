import {
  CoordFormat,
  GpsData,
  ResolutionPreset,
  TimestampMode,
  WatermarkPosition,
  WatermarkSettings,
  WatermarkSize,
} from '../types';

const MONTHS_SHORT = [
  'Jan',
  'Feb',
  'Mar',
  'Apr',
  'May',
  'Jun',
  'Jul',
  'Aug',
  'Sep',
  'Oct',
  'Nov',
  'Dec',
];
const DAYS_SHORT = ['Sun', 'Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat'];
const DAYS_FULL = [
  'Sunday',
  'Monday',
  'Tuesday',
  'Wednesday',
  'Thursday',
  'Friday',
  'Saturday',
];

const CREATED_BY_LABEL = 'Created by:';
const URDU_FOOTER_TEXT = 'شمائل احمد رندھاوا) پیرا فورس سرائے عالمگیر';
const URDU_FONT_FAMILY =
  '"Jameel Noori Nastaleeq", "Jameel Noori Nastaleeq Kasheeda", "Noto Nastaliq Urdu", "Urdu Typesetting", "Noto Naskh Arabic", "Noto Sans Arabic", sans-serif';

const COUNTRY_NAME_TO_ISO: Record<string, string> = {
  pakistan: 'PK',
  india: 'IN',
  afghanistan: 'AF',
  bangladesh: 'BD',
  china: 'CN',
  iran: 'IR',
  iraq: 'IQ',
  turkey: 'TR',
  turkiye: 'TR',
  'saudi arabia': 'SA',
  ksa: 'SA',
  'united arab emirates': 'AE',
  uae: 'AE',
  qatar: 'QA',
  kuwait: 'KW',
  oman: 'OM',
  bahrain: 'BH',
  egypt: 'EG',
  malaysia: 'MY',
  indonesia: 'ID',
  'united kingdom': 'GB',
  uk: 'GB',
  england: 'GB',
  'united states': 'US',
  'united states of america': 'US',
  usa: 'US',
  canada: 'CA',
  australia: 'AU',
  germany: 'DE',
  france: 'FR',
  italy: 'IT',
  spain: 'ES',
  japan: 'JP',
  'south korea': 'KR',
};

let urduFontInjected = false;
function ensureUrduNastaliqFontLoaded(): void {
  if (urduFontInjected || typeof document === 'undefined') return;
  urduFontInjected = true;
  if (!document.getElementById('shamail-urdu-nastaliq-stylesheet')) {
    const link = document.createElement('link');
    link.id = 'shamail-urdu-nastaliq-stylesheet';
    link.rel = 'stylesheet';
    link.href =
      'https://fonts.googleapis.com/css2?family=Noto+Nastaliq+Urdu:wght@400;500;600&display=swap';
    document.head.appendChild(link);
  }
}

function pad2(n: number): string {
  return String(n).padStart(2, '0');
}

function containsFlagEmoji(text: string): boolean {
  for (const ch of text) {
    const cp = ch.codePointAt(0) ?? 0;
    if (cp >= 0x1f1e6 && cp <= 0x1f1ff) return true;
  }
  return false;
}

function isoToFlagEmoji(iso: string): string {
  if (iso.length !== 2) return '';
  const a = iso.charCodeAt(0) & ~32;
  const b = iso.charCodeAt(1) & ~32;
  if (a < 65 || a > 90 || b < 65 || b > 90) return '';
  return String.fromCodePoint(0x1f1e6 + (a - 65), 0x1f1e6 + (b - 65));
}

function resolveCountryIso(countryCandidate: string): string | null {
  const cleaned = countryCandidate.trim();
  if (!cleaned) return null;
  if (/^[A-Za-z]{2}$/.test(cleaned)) {
    return cleaned.toUpperCase();
  }
  const direct = COUNTRY_NAME_TO_ISO[cleaned.toLowerCase()];
  if (direct) return direct;

  if (typeof Intl !== 'undefined' && typeof Intl.DisplayNames !== 'undefined') {
    try {
      const displayNames = new Intl.DisplayNames(['en'], { type: 'region' });
      const targetLower = cleaned.toLowerCase();
      for (let i = 65; i <= 90; i++) {
        for (let j = 65; j <= 90; j++) {
          const code = String.fromCharCode(i, j);
          const label = displayNames.of(code);
          if (label && label.toLowerCase() === targetLower) {
            return code;
          }
        }
      }
    } catch {
      // Ignore environment Intl limitations
    }
  }
  return null;
}

export function buildLocationHeadline(gps: GpsData): string {
  if (!gps.hasFix) return 'Waiting for GPS Satellite Fix...';
  const parts: string[] = [];
  const pushUnique = (val: string) => {
    const trimmed = val.trim();
    if (trimmed && !parts.some((p) => p.toLowerCase() === trimmed.toLowerCase())) {
      parts.push(trimmed);
    }
  };
  pushUnique(gps.city);
  pushUnique(gps.district);
  pushUnique(gps.province);
  pushUnique(gps.country);
  if (parts.length > 0) return parts.join(', ');
  return `GPS ${gps.latitude.toFixed(5)}°, ${gps.longitude.toFixed(5)}°`;
}

function buildLocationHeadlineWithFlag(gps: GpsData): string {
  const base = buildLocationHeadline(gps);
  if (!gps.hasFix || containsFlagEmoji(base)) return base;

  const fallbackCountry = base.includes(',') ? base.split(',').pop()?.trim() || '' : '';
  const countryCandidate = gps.country.trim() || fallbackCountry;
  if (!countryCandidate) return base;

  const iso = resolveCountryIso(countryCandidate);
  if (!iso) return base;
  const flag = isoToFlagEmoji(iso);
  return flag ? `${base} ${flag}` : base;
}

export function buildFullAddressLine(gps: GpsData): string {
  if (!gps.hasFix) return 'Grant Location permission or select a location on Map';
  if (gps.streetAddress.trim()) return gps.streetAddress.trim();
  if (gps.isOfflineAddress) {
    return `${gps.latitude.toFixed(6)}°, ${gps.longitude.toFixed(6)}°`;
  }
  return `${gps.latitude.toFixed(6)}°, ${gps.longitude.toFixed(6)}°`;
}

export function resolveTimestampMillis(
  settings: WatermarkSettings,
  gps: GpsData,
  nowMillis: number = Date.now()
): number {
  if (settings.timestampMode === TimestampMode.Manual) {
    return settings.manualTimestampMillis;
  }
  if (settings.timestampMode === TimestampMode.GpsFix) {
    return gps.hasFix && gps.gpsTimestampMillis > 0 ? gps.gpsTimestampMillis : nowMillis;
  }
  return nowMillis;
}

/**
 * Formats watermark timestamp cleanly with ZERO "Edited", "[Edited]", "Modified", or "(GPS)" suffixes.
 */
export function formatWatermarkTimestamp(
  settings: WatermarkSettings,
  gps: GpsData,
  nowMillis: number = Date.now()
): string {
  const targetMs = resolveTimestampMillis(settings, gps, nowMillis);
  return formatTimestampMillisClean(targetMs, settings.dateFormatPattern, settings.is24HourFormat);
}

export function formatTimestampMillisClean(
  targetMs: number,
  dateFormatPattern: WatermarkSettings['dateFormatPattern'],
  is24HourFormat: boolean
): string {
  const d = new Date(targetMs);
  const yyyy = d.getFullYear();
  const mm = pad2(d.getMonth() + 1);
  const dd = pad2(d.getDate());
  const mmm = MONTHS_SHORT[d.getMonth()];
  const eee = DAYS_SHORT[d.getDay()];
  const eeee = DAYS_FULL[d.getDay()];

  let datePart = `${eeee}, ${dd}/${mm}/${yyyy}`;
  if (dateFormatPattern === 'yyyy-MM-dd') {
    datePart = `${eeee}, ${yyyy}-${mm}-${dd}`;
  } else if (dateFormatPattern === 'EEE, dd MMM yyyy') {
    datePart = `${eee}, ${dd} ${mmm} ${yyyy}`;
  } else if (dateFormatPattern === 'dd/MM/yyyy' || dateFormatPattern === 'dd MMM yyyy') {
    datePart = `${eeee}, ${dd}/${mm}/${yyyy}`;
  }

  let timePart = '';
  if (is24HourFormat) {
    timePart = `${pad2(d.getHours())}:${pad2(d.getMinutes())}`;
  } else {
    const rawHours = d.getHours();
    const ampm = rawHours >= 12 ? 'PM' : 'AM';
    const h12 = rawHours % 12 || 12;
    timePart = `${pad2(h12)}:${pad2(d.getMinutes())} ${ampm}`;
  }

  const offsetMinTotal = -d.getTimezoneOffset();
  const sign = offsetMinTotal >= 0 ? '+' : '-';
  const absMin = Math.abs(offsetMinTotal);
  const tzH = pad2(Math.floor(absMin / 60));
  const tzM = pad2(absMin % 60);
  const gmtPart = `GMT ${sign}${tzH}:${tzM}`;

  return `${datePart} ${timePart} ${gmtPart}`;
}

function toDms(coord: number, isLat: boolean): string {
  const hemi = isLat ? (coord >= 0 ? 'N' : 'S') : coord >= 0 ? 'E' : 'W';
  const absVal = Math.abs(coord);
  const deg = Math.floor(absVal);
  const minFull = (absVal - deg) * 60;
  const min = Math.floor(minFull);
  const sec = (minFull - min) * 60;
  return `${deg}°${pad2(min)}'${sec.toFixed(1).padStart(4, '0')}"${hemi}`;
}

export function formatWatermarkCoordinates(settings: WatermarkSettings, gps: GpsData): string {
  if (!gps.hasFix) return 'Lat --.------° Long --.------°';
  if (settings.coordFormat === CoordFormat.Dms) {
    return `Lat ${toDms(gps.latitude, true)} Long ${toDms(gps.longitude, false)}`;
  }
  return `Lat ${gps.latitude.toFixed(6)}° Long ${gps.longitude.toFixed(6)}°`;
}

function wrapCanvasText(
  ctx: CanvasRenderingContext2D,
  text: string,
  maxWidth: number,
  maxLines: number
): string[] {
  const words = text.split(/\s+/).filter(Boolean);
  if (words.length === 0) return [];
  const lines: string[] = [];
  let current = words[0];

  for (let i = 1; i < words.length; i++) {
    const candidate = `${current} ${words[i]}`;
    if (ctx.measureText(candidate).width <= maxWidth) {
      current = candidate;
    } else {
      lines.push(current);
      current = words[i];
      if (lines.length === maxLines - 1) {
        const rest = [current, ...words.slice(i + 1)].join(' ');
        let clipped = rest;
        while (clipped.length > 3 && ctx.measureText(`${clipped}…`).width > maxWidth) {
          clipped = clipped.slice(0, -1);
        }
        current = clipped === rest ? rest : `${clipped}…`;
        break;
      }
    }
  }
  lines.push(current);
  return lines.slice(0, maxLines);
}

function drawRoundedRect(
  ctx: CanvasRenderingContext2D,
  x: number,
  y: number,
  w: number,
  h: number,
  r: number
) {
  const radius = Math.min(r, w / 2, h / 2);
  ctx.beginPath();
  ctx.moveTo(x + radius, y);
  ctx.arcTo(x + w, y, x + w, y + h, radius);
  ctx.arcTo(x + w, y + h, x, y + h, radius);
  ctx.arcTo(x, y + h, x, y, radius);
  ctx.arcTo(x, y, x + w, y, radius);
  ctx.closePath();
}

export function measureAndDrawWatermarkCard(params: {
  ctx: CanvasRenderingContext2D;
  containerW: number;
  containerH: number | null;
  settings: WatermarkSettings;
  gps: GpsData;
  logoImg: HTMLImageElement | null;
  nowMillis: number;
}): { cardHeight: number; timestampText: string } {
  ensureUrduNastaliqFontLoaded();

  const { ctx, containerW, containerH, settings, gps, logoImg, nowMillis } = params;
  const timestampText = formatWatermarkTimestamp(settings, gps, nowMillis);

  if (settings.showWatermark === false) {
    return { cardHeight: 0, timestampText };
  }

  const outerMargin = Math.max(12, containerW * 0.028);
  const availW = Math.max(160, containerW - outerMargin * 2);
  const baseFraction =
    settings.watermarkSize === WatermarkSize.Small
      ? 0.76
      : settings.watermarkSize === WatermarkSize.Large
        ? 0.96
        : 0.9;

  const isFullLandscapePhoto =
    containerH !== null && containerH >= containerW * 0.45 && containerW > containerH;
  const cardWidth = isFullLandscapePhoto
    ? Math.max(180, Math.min(availW * baseFraction, containerH * 1.5))
    : availW * baseFraction;

  const unit = (cardWidth / 460) * settings.fontScale;
  const padX = Math.max(10, 18 * unit);
  const padTop = Math.max(9, 15 * unit);
  const padBottom = Math.max(7, 10 * unit);
  const cornerRadius = Math.max(10, 16 * unit);

  const hasLogo = settings.showLogo;
  const logoSize = hasLogo ? Math.max(42, 68 * unit) : 0;
  const logoOverhang = hasLogo ? logoSize * 0.4 : 0;
  const logoRightInset = 14 * unit;
  const logoInsideDrop = logoSize - logoOverhang;

  const fullInnerWidth = Math.max(90, cardWidth - padX * 2);
  const logoClearWidth = hasLogo
    ? Math.max(80, cardWidth - padX - logoRightInset - logoSize - 12 * unit)
    : fullInnerWidth;

  const titleFont = `500 ${Math.max(12, Math.round(22.5 * unit))}px "Plus Jakarta Sans", ui-sans-serif, system-ui, sans-serif`;
  const detailFont = `400 ${Math.max(9, Math.round(14.5 * unit))}px "Plus Jakarta Sans", ui-sans-serif, system-ui, sans-serif`;

  const titleLineH = 26.5 * unit;
  const detailLineH = 18.5 * unit;
  const titleGap = 5.5 * unit;
  const detailGap = 4.2 * unit;

  ctx.save();
  ctx.direction = 'ltr';
  ctx.textAlign = 'left';

  let contentY = padTop;

  ctx.font = titleFont;
  const locLines = settings.showLocationTitle
    ? wrapCanvasText(ctx, buildLocationHeadlineWithFlag(gps), logoClearWidth, 2)
    : [];
  if (locLines.length) {
    contentY += locLines.length * titleLineH + titleGap;
  }

  ctx.font = detailFont;
  const addrMaxW = hasLogo && contentY < logoInsideDrop + 2 * unit ? logoClearWidth : fullInnerWidth;
  const addrLines = settings.showFullAddress
    ? wrapCanvasText(ctx, buildFullAddressLine(gps), addrMaxW, 3)
    : [];
  if (addrLines.length) {
    contentY += addrLines.length * detailLineH + detailGap;
  }

  const coordMaxW = hasLogo && contentY < logoInsideDrop + 2 * unit ? logoClearWidth : fullInnerWidth;
  const coordLines = settings.showCoordinates
    ? wrapCanvasText(ctx, formatWatermarkCoordinates(settings, gps), coordMaxW, 2)
    : [];
  if (coordLines.length) {
    contentY += coordLines.length * detailLineH + detailGap;
  }

  const dtMaxW = hasLogo && contentY < logoInsideDrop + 2 * unit ? logoClearWidth : fullInnerWidth;
  const dateTimeLines = settings.showDateTime
    ? wrapCanvasText(ctx, timestampText, dtMaxW, 2)
    : [];
  if (dateTimeLines.length) {
    contentY += dateTimeLines.length * detailLineH;
  }

  const minPreDividerBottom = hasLogo ? Math.max(contentY, logoInsideDrop + 6 * unit) : contentY;
  const dividerRelY = minPreDividerBottom + 10 * unit;

  // Measure Created by: (LTR) + Urdu text (RTL) separately and auto-fit inside cardWidth
  let enFontPx = Math.max(9, 13.5 * unit);
  let urduFontPx = Math.max(10, 14.5 * unit);
  const labelUrduGap = 6.5 * unit;

  const setFooterEnFont = () => {
    ctx.direction = 'ltr';
    ctx.font = `400 ${enFontPx.toFixed(1)}px "Plus Jakarta Sans", ui-sans-serif, system-ui, sans-serif`;
  };
  const setFooterUrduFont = () => {
    ctx.direction = 'rtl';
    ctx.font = `400 ${urduFontPx.toFixed(1)}px ${URDU_FONT_FAMILY}`;
  };

  setFooterEnFont();
  let enMetrics = ctx.measureText(CREATED_BY_LABEL);
  setFooterUrduFont();
  let urduMetrics = ctx.measureText(URDU_FOOTER_TEXT);

  const rawCombinedW = enMetrics.width + labelUrduGap + urduMetrics.width;
  if (rawCombinedW > fullInnerWidth && rawCombinedW > 1) {
    const shrink = Math.max(0.55, Math.min(1, fullInnerWidth / rawCombinedW));
    enFontPx *= shrink;
    urduFontPx *= shrink;
    setFooterEnFont();
    enMetrics = ctx.measureText(CREATED_BY_LABEL);
    setFooterUrduFont();
    urduMetrics = ctx.measureText(URDU_FOOTER_TEXT);
  }

  const enAscent = enMetrics.actualBoundingBoxAscent || enFontPx * 0.8;
  const enDescent = enMetrics.actualBoundingBoxDescent || enFontPx * 0.25;
  const urduAscent = urduMetrics.actualBoundingBoxAscent || urduFontPx * 0.95;
  const urduDescent = urduMetrics.actualBoundingBoxDescent || urduFontPx * 0.42;
  const maxFooterAscent = Math.max(enAscent, urduAscent);
  const maxFooterDescent = Math.max(enDescent, urduDescent);
  const footerZoneHeight = Math.max(26 * unit, maxFooterAscent + maxFooterDescent + 8 * unit);

  const panelHeight = dividerRelY + footerZoneHeight + padBottom * 0.35;
  const totalVisualHeight = logoOverhang + panelHeight;

  if (containerH === null) {
    ctx.restore();
    return { cardHeight: totalVisualHeight, timestampText };
  }

  const cardLeft =
    settings.watermarkPosition === WatermarkPosition.BottomLeft
      ? outerMargin
      : settings.watermarkPosition === WatermarkPosition.BottomRight
        ? containerW - outerMargin - cardWidth
        : (containerW - cardWidth) / 2;

  const panelTop =
    containerH > totalVisualHeight + outerMargin * 2
      ? Math.max(logoOverhang + 4, containerH - outerMargin - panelHeight)
      : Math.max(logoOverhang + 2, (containerH - totalVisualHeight) / 2 + logoOverhang);

  // Semi-transparent black / dark charcoal background (zero gold or yellow theme)
  const alpha = Math.min(0.95, Math.max(0.3, settings.watermarkOpacity / 100));
  drawRoundedRect(ctx, cardLeft, panelTop, cardWidth, panelHeight, cornerRadius);
  ctx.fillStyle = `rgba(20, 22, 24, ${alpha})`;
  ctx.fill();

  // Subtle, thin neutral gray border
  ctx.lineWidth = Math.max(1, 1.1 * unit);
  ctx.strokeStyle = 'rgba(255, 255, 255, 0.22)';
  ctx.stroke();

  // Draw GPS info lines with subtle legibility shadow over light & dark photos
  ctx.save();
  ctx.direction = 'ltr';
  ctx.textAlign = 'left';
  ctx.textBaseline = 'top';
  ctx.shadowColor = 'rgba(0, 0, 0, 0.48)';
  ctx.shadowBlur = Math.max(1, 1.8 * unit);
  ctx.shadowOffsetX = 0;
  ctx.shadowOffsetY = Math.max(0.5, 0.8 * unit);

  const textStartX = cardLeft + padX;
  let drawY = panelTop + padTop;

  if (locLines.length) {
    ctx.font = titleFont;
    ctx.fillStyle = '#FFFFFF';
    locLines.forEach((line) => {
      ctx.fillText(line, textStartX, drawY);
      drawY += titleLineH;
    });
    drawY += titleGap;
  }

  if (addrLines.length) {
    ctx.font = detailFont;
    ctx.fillStyle = '#D8DCE2';
    addrLines.forEach((line) => {
      ctx.fillText(line, textStartX, drawY);
      drawY += detailLineH;
    });
    drawY += detailGap;
  }

  if (coordLines.length) {
    ctx.font = detailFont;
    ctx.fillStyle = '#D8DCE2';
    coordLines.forEach((line) => {
      ctx.fillText(line, textStartX, drawY);
      drawY += detailLineH;
    });
    drawY += detailGap;
  }

  if (dateTimeLines.length) {
    ctx.font = detailFont;
    ctx.fillStyle = '#D8DCE2';
    dateTimeLines.forEach((line) => {
      ctx.fillText(line, textStartX, drawY);
      drawY += detailLineH;
    });
  }
  ctx.restore();

  // Thin, subtle horizontal gray divider line above Created by footer
  const dividerY = panelTop + dividerRelY;
  const dividerStartX = cardLeft + 10 * unit;
  const dividerEndX = cardLeft + cardWidth - 10 * unit;
  const divGrad = ctx.createLinearGradient(dividerStartX, dividerY, dividerEndX, dividerY);
  divGrad.addColorStop(0, 'rgba(255, 255, 255, 0)');
  divGrad.addColorStop(0.06, 'rgba(235, 238, 242, 0.45)');
  divGrad.addColorStop(0.94, 'rgba(235, 238, 242, 0.45)');
  divGrad.addColorStop(1, 'rgba(255, 255, 255, 0)');

  ctx.beginPath();
  ctx.moveTo(dividerStartX, dividerY);
  ctx.lineTo(dividerEndX, dividerY);
  ctx.lineWidth = Math.max(1, 1.0 * unit);
  ctx.strokeStyle = divGrad;
  ctx.stroke();

  // Draw Created by: (separate LTR) + exact Urdu text (separate RTL) inside the footer
  const totalFooterTextWidth = enMetrics.width + labelUrduGap + urduMetrics.width;
  const footerGroupLeft = cardLeft + (cardWidth - totalFooterTextWidth) / 2;
  const footerBaselineY =
    dividerY + (footerZoneHeight - (maxFooterAscent + maxFooterDescent)) / 2 + maxFooterAscent;

  ctx.save();
  ctx.fillStyle = '#FFFFFF';
  ctx.textBaseline = 'alphabetic';
  ctx.shadowColor = 'rgba(0, 0, 0, 0.48)';
  ctx.shadowBlur = Math.max(1, 1.6 * unit);
  ctx.shadowOffsetX = 0;
  ctx.shadowOffsetY = Math.max(0.5, 0.7 * unit);

  setFooterEnFont();
  ctx.textAlign = 'left';
  ctx.fillText(CREATED_BY_LABEL, footerGroupLeft, footerBaselineY);

  const urduLeftX = footerGroupLeft + enMetrics.width + labelUrduGap;
  setFooterUrduFont();
  ctx.textAlign = 'left';
  ctx.fillText(URDU_FOOTER_TEXT, urduLeftX, footerBaselineY);
  ctx.restore();

  // Short thin gray lines beside the footer when horizontal space permits
  const sideGap = 12 * unit;
  const availSideSpace = footerGroupLeft - (cardLeft + padX) - sideGap;
  if (availSideSpace >= 14 * unit) {
    const sideLineLen = Math.min(38 * unit, availSideSpace);
    const sideLineY = footerBaselineY - enAscent * 0.36;
    ctx.strokeStyle = 'rgba(226, 232, 240, 0.53)';
    ctx.lineWidth = Math.max(1, 1.1 * unit);

    ctx.beginPath();
    ctx.moveTo(footerGroupLeft - sideGap - sideLineLen, sideLineY);
    ctx.lineTo(footerGroupLeft - sideGap, sideLineY);
    ctx.stroke();

    const footerGroupRight = footerGroupLeft + totalFooterTextWidth;
    ctx.beginPath();
    ctx.moveTo(footerGroupRight + sideGap, sideLineY);
    ctx.lineTo(footerGroupRight + sideGap + sideLineLen, sideLineY);
    ctx.stroke();
  }

  // Draw circular SHAMAIL GPS logo at upper-right corner of the watermark panel
  if (hasLogo) {
    const logoLeft = cardLeft + cardWidth - logoRightInset - logoSize;
    const logoTop = panelTop - logoOverhang;
    const cx = logoLeft + logoSize / 2;
    const cy = logoTop + logoSize / 2;
    const rad = logoSize / 2;

    ctx.save();
    ctx.beginPath();
    ctx.arc(cx, cy, rad, 0, Math.PI * 2);
    ctx.closePath();
    ctx.fillStyle = '#0D0F12';
    ctx.fill();
    ctx.clip();

    if (logoImg && logoImg.naturalWidth > 0 && logoImg.naturalHeight > 0) {
      const minSide = Math.min(logoImg.naturalWidth, logoImg.naturalHeight);
      const sx = (logoImg.naturalWidth - minSide) / 2;
      const sy = (logoImg.naturalHeight - minSide) / 2;
      ctx.drawImage(logoImg, sx, sy, minSide, minSide, logoLeft, logoTop, logoSize, logoSize);
    }
    ctx.restore();

    ctx.beginPath();
    ctx.arc(cx, cy, rad, 0, Math.PI * 2);
    ctx.closePath();
    ctx.lineWidth = Math.max(1, 1.0 * unit);
    ctx.strokeStyle = 'rgba(255, 255, 255, 0.19)';
    ctx.stroke();
  }

  ctx.restore();
  return { cardHeight: totalVisualHeight, timestampText };
}

export async function loadImageElement(src: string): Promise<HTMLImageElement | null> {
  return new Promise((resolve) => {
    const img = new Image();
    img.crossOrigin = 'anonymous';
    img.onload = () => resolve(img);
    img.onerror = () => resolve(null);
    img.src = src;
  });
}

export function captureRawBasePhoto(params: {
  sourceElement: HTMLVideoElement | HTMLImageElement;
  isMirroredFrontCamera: boolean;
  resolutionPreset: ResolutionPreset;
  jpegQuality: number;
}): { rawBaseDataUrl: string; width: number; height: number } {
  const { sourceElement, isMirroredFrontCamera, resolutionPreset, jpegQuality } = params;

  const rawWidth =
    sourceElement instanceof HTMLVideoElement
      ? sourceElement.videoWidth || 1920
      : sourceElement.naturalWidth || 1920;
  const rawHeight =
    sourceElement instanceof HTMLVideoElement
      ? sourceElement.videoHeight || 1080
      : sourceElement.naturalHeight || 1080;

  let targetW = rawWidth;
  let targetH = rawHeight;
  const maxEdgeTarget =
    resolutionPreset === ResolutionPreset.Fhd
      ? 1920
      : resolutionPreset === ResolutionPreset.Hd
        ? 1280
        : 0;

  if (maxEdgeTarget > 0) {
    const longEdge = Math.max(rawWidth, rawHeight);
    if (longEdge > maxEdgeTarget) {
      const ratio = maxEdgeTarget / longEdge;
      targetW = Math.max(1, Math.round(rawWidth * ratio));
      targetH = Math.max(1, Math.round(rawHeight * ratio));
    }
  }

  const canvas = document.createElement('canvas');
  canvas.width = targetW;
  canvas.height = targetH;
  const ctx = canvas.getContext('2d');
  if (!ctx) {
    throw new Error('Canvas 2D context unavailable');
  }

  ctx.save();
  if (isMirroredFrontCamera) {
    ctx.translate(targetW, 0);
    ctx.scale(-1, 1);
  }
  ctx.drawImage(sourceElement, 0, 0, targetW, targetH);
  ctx.restore();

  const qualityDecimal = Math.min(1, Math.max(0.85, jpegQuality / 100));
  return {
    rawBaseDataUrl: canvas.toDataURL('image/jpeg', qualityDecimal),
    width: targetW,
    height: targetH,
  };
}

export async function composeWatermarkedPhoto(params: {
  sourceElement: HTMLVideoElement | HTMLImageElement;
  isMirroredFrontCamera: boolean;
  settings: WatermarkSettings;
  gps: GpsData;
  defaultLogoUrl: string;
  captureMillis: number;
}): Promise<{ dataUrl: string; width: number; height: number; timestampText: string }> {
  const { sourceElement, isMirroredFrontCamera, settings, gps, defaultLogoUrl, captureMillis } =
    params;

  const rawWidth =
    sourceElement instanceof HTMLVideoElement
      ? sourceElement.videoWidth || 1920
      : sourceElement.naturalWidth || 1920;
  const rawHeight =
    sourceElement instanceof HTMLVideoElement
      ? sourceElement.videoHeight || 1080
      : sourceElement.naturalHeight || 1080;

  let targetW = rawWidth;
  let targetH = rawHeight;
  const maxEdgeTarget =
    settings.resolutionPreset === ResolutionPreset.Fhd
      ? 1920
      : settings.resolutionPreset === ResolutionPreset.Hd
        ? 1280
        : 0;

  if (maxEdgeTarget > 0) {
    const longEdge = Math.max(rawWidth, rawHeight);
    if (longEdge > maxEdgeTarget) {
      const ratio = maxEdgeTarget / longEdge;
      targetW = Math.max(1, Math.round(rawWidth * ratio));
      targetH = Math.max(1, Math.round(rawHeight * ratio));
    }
  }

  const canvas = document.createElement('canvas');
  canvas.width = targetW;
  canvas.height = targetH;
  const ctx = canvas.getContext('2d');
  if (!ctx) {
    throw new Error('Canvas 2D context unavailable');
  }

  ctx.save();
  if (isMirroredFrontCamera) {
    ctx.translate(targetW, 0);
    ctx.scale(-1, 1);
  }
  ctx.drawImage(sourceElement, 0, 0, targetW, targetH);
  ctx.restore();

  ensureUrduNastaliqFontLoaded();
  if (typeof document !== 'undefined' && document.fonts?.ready) {
    await Promise.race([
      document.fonts.ready,
      new Promise((resolve) => setTimeout(resolve, 300)),
    ]);
  }

  let logoImg: HTMLImageElement | null = null;
  if (settings.showLogo && settings.showWatermark !== false) {
    const logoSrc = settings.customLogoDataUrl || defaultLogoUrl;
    logoImg = await loadImageElement(logoSrc);
  }

  const { timestampText } = measureAndDrawWatermarkCard({
    ctx,
    containerW: targetW,
    containerH: targetH,
    settings,
    gps,
    logoImg,
    nowMillis: captureMillis,
  });

  const qualityDecimal = Math.min(1, Math.max(0.7, settings.jpegQuality / 100));
  const dataUrl = canvas.toDataURL('image/jpeg', qualityDecimal);

  return {
    dataUrl,
    width: targetW,
    height: targetH,
    timestampText,
  };
}
