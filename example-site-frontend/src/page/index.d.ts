declare module "*.png" {
    const content: string;
    export default content;
}

declare module "*.less" {
    const classes: {readonly [key: string]: string};
    export default classes;
}
