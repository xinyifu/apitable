import { integrateCdnHost, ThemeName } from '@apitable/core';
import { IActionType, ITriggerType } from './interface';

const WECOM_SERVICE_ICON = '/static/icon/signin/signin_img_wecom.png';

interface IAutomationService {
  slug?: string;
  logo?: string;
}

export const getAutomationServiceIcon = (service?: IAutomationService) => {
  if (service?.slug === 'wecom') {
    return WECOM_SERVICE_ICON;
  }
  const logo = service?.logo || '';
  if (logo.startsWith('/static/')) {
    return logo;
  }
  return integrateCdnHost(logo);
};

export const covertThemeIcon = (data: (ITriggerType | IActionType)[] | undefined, theme: ThemeName) => {
  return (
    (data?.map((item) => {
      const logo = (theme === ThemeName.Dark ? item.service.themeLogo?.dark : item.service.themeLogo?.light) || item.service.logo;
      return {
        ...item,
        service: {
          ...item.service,
          logo: item.service.slug === 'wecom' ? WECOM_SERVICE_ICON : logo,
        },
      };
    }) as any) || []
  );
};
