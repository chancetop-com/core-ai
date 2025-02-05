import {ComponentType} from "react";

export interface FTIRoute {
    name: string;
    path: string;
    menuRole: string;
    pageRole: string | null;
    menu?: string;
    icon?: React.ReactNode;
    iconType?: string;
    hash?: string;
    component?: ComponentType<any>;
    children?: SubFTIRoute[];
    longMenu?: string;
    hidden?: boolean;
    customBreadCrumbs?: React.ReactElement;
    redirect?: string;
    defaultPath?: string;
    externalLink?: string;
}

export interface SubFTIRoute extends Omit<Partial<FTIRoute>, "name" | "path"> {
    name: string;
    path: string;
}
